package com.aifacestudio.ui.viewmodel

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aifacestudio.core.preferences.AppPreferences
import com.aifacestudio.data.billing.BillingRepository
import com.aifacestudio.data.billing.SubscriptionState
import com.aifacestudio.data.local.ExportHistoryDao
import com.aifacestudio.data.local.ExportHistoryEntity
import com.aifacestudio.data.local.ProjectEntity
import com.aifacestudio.data.model.ExportQuality
import com.aifacestudio.data.model.ImageFormat
import com.aifacestudio.data.model.MediaType
import com.aifacestudio.data.model.MediaValidationResult
import com.aifacestudio.data.model.ProjectDetails
import com.aifacestudio.data.model.ProjectStatus
import com.aifacestudio.data.model.ShareTarget
import com.aifacestudio.data.model.VideoResolution
import com.aifacestudio.domain.MediaExporter
import com.aifacestudio.domain.MediaValidator
import com.aifacestudio.domain.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SplashViewModel @Inject constructor(
    preferences: AppPreferences
) : ViewModel() {
    val onboardingCompleted: StateFlow<Boolean?> = preferences.onboardingCompleted
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferences: AppPreferences
) : ViewModel() {
    fun complete() {
        viewModelScope.launch { preferences.setOnboardingCompleted(true) }
    }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    repository: ProjectRepository,
    billingRepository: BillingRepository
) : ViewModel() {
    val recentProjects: StateFlow<List<ProjectEntity>> = repository.observeRecentProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val subscriptionState: StateFlow<SubscriptionState> = billingRepository.subscriptionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubscriptionState())
}

data class UploadUiState(
    val mediaType: MediaType,
    val sourceResult: MediaValidationResult? = null,
    val faceResults: List<MediaValidationResult> = emptyList(),
    val validatingSource: Boolean = false,
    val validatingFace: Boolean = false,
    val creatingDraft: Boolean = false
) {
    val faceResult: MediaValidationResult?
        get() = faceResults.firstOrNull()

    val canPreview: Boolean
        get() = sourceResult?.isValid == true &&
            faceResults.isNotEmpty() &&
            faceResults.all { it.isValid } &&
            !validatingSource &&
            !validatingFace &&
            !creatingDraft
}

sealed interface UploadEvent {
    data class NavigateToPreview(val projectId: String) : UploadEvent
    data class Message(val text: String) : UploadEvent
}

@HiltViewModel
class UploadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mediaValidator: MediaValidator,
    private val repository: ProjectRepository
) : ViewModel() {
    private val mediaType = MediaType.valueOf(checkNotNull(savedStateHandle["mediaType"]))
    private val _state = MutableStateFlow(UploadUiState(mediaType))
    val state: StateFlow<UploadUiState> = _state
    private val eventsChannel = Channel<UploadEvent>(Channel.BUFFERED)
    val events: Flow<UploadEvent> = eventsChannel.receiveAsFlow()

    fun onSourceSelected(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(validatingSource = true) }
            val result = runCatching { mediaValidator.validateSource(uri, mediaType) }
                .getOrElse { MediaValidationResult(null, errors = listOf(it.message ?: "Source validation failed.")) }
            _state.update { it.copy(sourceResult = result, validatingSource = false) }
        }
    }

    fun onFaceSelected(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(validatingFace = true) }
            val result = runCatching { mediaValidator.validateFace(uri) }
                .getOrElse { MediaValidationResult(null, errors = listOf(it.message ?: "Face validation failed.")) }
            _state.update { it.copy(faceResults = listOf(result), validatingFace = false) }
        }
    }

    fun onAdditionalFacesSelected(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(validatingFace = true) }
            val current = _state.value.faceResults
            val remainingSlots = (MAX_REPLACEMENT_FACES - current.size).coerceAtLeast(0)
            val additions = uris.take(remainingSlots).map { uri ->
                runCatching { mediaValidator.validateFace(uri) }
                    .getOrElse { MediaValidationResult(null, errors = listOf(it.message ?: "Face validation failed.")) }
            }
            _state.update { it.copy(faceResults = current + additions, validatingFace = false) }
        }
    }

    fun removeFace(index: Int) {
        _state.update { state ->
            state.copy(faceResults = state.faceResults.filterIndexed { itemIndex, _ -> itemIndex != index })
        }
    }

    fun createDraft() {
        val snapshot = state.value
        val source = snapshot.sourceResult?.media
        val faces = snapshot.faceResults.mapNotNull { it.media }
        if (source == null || faces.isEmpty() || !snapshot.canPreview) {
            viewModelScope.launch { eventsChannel.send(UploadEvent.Message("Select valid source and face inputs first.")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(creatingDraft = true) }
            val result = runCatching { repository.createDraft(mediaType, source, faces) }
            _state.update { it.copy(creatingDraft = false) }
            result.onSuccess { eventsChannel.send(UploadEvent.NavigateToPreview(it)) }
                .onFailure { eventsChannel.send(UploadEvent.Message(it.message ?: "Could not create project.")) }
        }
    }

    private companion object {
        private const val MAX_REPLACEMENT_FACES = 12
    }
}

data class PreviewSettings(
    val imageFormat: ImageFormat = ImageFormat.JPG,
    val quality: ExportQuality = ExportQuality.STANDARD,
    val videoResolution: VideoResolution = VideoResolution.P1080,
    val fps: Int = 30,
    val multiFace: Boolean = false,
    val videoTrimStartMs: Long = 0L,
    val videoTrimEndMs: Long = 0L,
    val visibleWatermark: Boolean = true,
    val metadataWatermark: Boolean = true,
    val consentAccepted: Boolean = false
)

data class PreviewUiState(
    val details: ProjectDetails? = null,
    val settings: PreviewSettings = PreviewSettings(),
    val starting: Boolean = false
)

sealed interface PreviewEvent {
    data class NavigateToProcessing(val projectId: String) : PreviewEvent
    data class Message(val text: String) : PreviewEvent
}

@HiltViewModel
class PreviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository
) : ViewModel() {
    private val projectId: String = checkNotNull(savedStateHandle["projectId"])
    private val settings = MutableStateFlow(PreviewSettings())
    private val starting = MutableStateFlow(false)
    private val details = repository.observeProjectDetails(projectId)
    private val eventsChannel = Channel<PreviewEvent>(Channel.BUFFERED)
    val events: Flow<PreviewEvent> = eventsChannel.receiveAsFlow()

    val state: StateFlow<PreviewUiState> = combine(details, settings, starting) { projectDetails, previewSettings, isStarting ->
        PreviewUiState(projectDetails, previewSettings, isStarting)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PreviewUiState())

    init {
        viewModelScope.launch {
            details.collect { projectDetails ->
                val project = projectDetails?.project ?: return@collect
                settings.value = PreviewSettings(
                    imageFormat = project.imageFormat,
                    quality = project.quality,
                    videoResolution = project.videoResolution,
                    fps = project.fps,
                    multiFace = project.multiFace,
                    videoTrimStartMs = project.videoTrimStartMs,
                    videoTrimEndMs = project.videoTrimEndMs,
                    visibleWatermark = project.visibleWatermark,
                    metadataWatermark = project.metadataWatermark,
                    consentAccepted = project.consentAccepted
                )
            }
        }
    }

    fun updateSettings(transform: (PreviewSettings) -> PreviewSettings) {
        settings.update(transform)
    }

    fun startProcessing() {
        val currentSettings = settings.value
        if (!currentSettings.consentAccepted) {
            viewModelScope.launch { eventsChannel.send(PreviewEvent.Message("Confirm consent and ownership before processing.")) }
            return
        }
        viewModelScope.launch {
            starting.value = true
            val result = runCatching {
                repository.updateSettings(
                    projectId = projectId,
                    imageFormat = currentSettings.imageFormat,
                    quality = currentSettings.quality,
                    videoResolution = currentSettings.videoResolution,
                    fps = currentSettings.fps,
                    multiFace = currentSettings.multiFace,
                    videoTrimStartMs = currentSettings.videoTrimStartMs,
                    videoTrimEndMs = currentSettings.videoTrimEndMs,
                    visibleWatermark = currentSettings.visibleWatermark,
                    metadataWatermark = currentSettings.metadataWatermark,
                    consentAccepted = currentSettings.consentAccepted
                )
                repository.enqueueProcessing(projectId)
            }
            starting.value = false
            result.onSuccess { eventsChannel.send(PreviewEvent.NavigateToProcessing(projectId)) }
                .onFailure { eventsChannel.send(PreviewEvent.Message(it.message ?: "Unable to start processing.")) }
        }
    }
}

data class ProcessingUiState(
    val details: ProjectDetails? = null,
    val progress: Int = 0,
    val message: String = "Queued",
    val status: ProjectStatus = ProjectStatus.DRAFT
)

@HiltViewModel
class ProcessingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository
) : ViewModel() {
    private val projectId: String = checkNotNull(savedStateHandle["projectId"])
    val state: StateFlow<ProcessingUiState> = repository.observeProjectDetails(projectId)
        .map { details ->
            val job = details?.job
            ProcessingUiState(
                details = details,
                progress = job?.progress ?: if (details?.project?.status == ProjectStatus.COMPLETED) 100 else 0,
                message = job?.statusMessage ?: "Queued",
                status = details?.project?.status ?: ProjectStatus.DRAFT
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProcessingUiState())

    fun cancel() {
        viewModelScope.launch { repository.cancelProcessing(projectId) }
    }
}

data class ResultUiState(
    val details: ProjectDetails? = null,
    val saving: Boolean = false
)

sealed interface ResultEvent {
    data class Message(val text: String) : ResultEvent
    data class Share(val intent: Intent) : ResultEvent
    data object NavigateHome : ResultEvent
}

@HiltViewModel
class ResultViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository,
    private val exporter: MediaExporter
) : ViewModel() {
    private val projectId: String = checkNotNull(savedStateHandle["projectId"])
    private val saving = MutableStateFlow(false)
    private val eventsChannel = Channel<ResultEvent>(Channel.BUFFERED)
    val events: Flow<ResultEvent> = eventsChannel.receiveAsFlow()

    val state: StateFlow<ResultUiState> = combine(
        repository.observeProjectDetails(projectId),
        saving
    ) { details, isSaving ->
        ResultUiState(details, isSaving)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResultUiState())

    fun save(destination: String) {
        val project = state.value.details?.project ?: return
        viewModelScope.launch {
            saving.value = true
            val result = runCatching { exporter.saveToGallery(project, destination) }
            saving.value = false
            result.onSuccess { eventsChannel.send(ResultEvent.Message("Saved to $destination.")) }
                .onFailure { eventsChannel.send(ResultEvent.Message(it.message ?: "Save failed.")) }
        }
    }

    fun share(target: ShareTarget) {
        val project = state.value.details?.project ?: return
        viewModelScope.launch {
            exporter.recordShare(project, target)
            eventsChannel.send(ResultEvent.Share(exporter.buildShareIntent(project, target)))
        }
    }

    fun delete() {
        viewModelScope.launch {
            repository.deleteProject(projectId)
            eventsChannel.send(ResultEvent.NavigateHome)
        }
    }

    fun reportContent() {
        viewModelScope.launch {
            eventsChannel.send(ResultEvent.Message("Report received for review."))
        }
    }
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: ProjectRepository
) : ViewModel() {
    val projects: StateFlow<List<ProjectEntity>> = repository.observeAllProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val eventsChannel = Channel<HistoryEvent>(Channel.BUFFERED)
    val events: Flow<HistoryEvent> = eventsChannel.receiveAsFlow()

    fun delete(projectId: String) {
        viewModelScope.launch { repository.deleteProject(projectId) }
    }

    fun duplicate(projectId: String) {
        viewModelScope.launch {
            repository.duplicateProject(projectId)?.let {
                eventsChannel.send(HistoryEvent.NavigateToPreview(it))
            }
        }
    }
}

sealed interface HistoryEvent {
    data class NavigateToPreview(val projectId: String) : HistoryEvent
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    billingRepository: BillingRepository,
    exportHistoryDao: ExportHistoryDao
) : ViewModel() {
    val subscriptionState: StateFlow<SubscriptionState> = billingRepository.subscriptionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubscriptionState())

    val exports: StateFlow<List<ExportHistoryEntity>> = exportHistoryDao.observeAllExports()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val promoteToPremium: () -> Unit = { billingRepository.markPremiumForPreview() }
}
