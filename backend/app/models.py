from enum import Enum
from pydantic import BaseModel, Field


class MediaType(str, Enum):
    IMAGE = "IMAGE"
    VIDEO = "VIDEO"
    GIF = "GIF"


class ImageFormat(str, Enum):
    JPG = "JPG"
    PNG = "PNG"
    WEBP = "WEBP"


class ExportQuality(str, Enum):
    STANDARD = "STANDARD"
    HIGH = "HIGH"
    ULTRA = "ULTRA"


class VideoResolution(str, Enum):
    P720 = "P720"
    P1080 = "P1080"
    P4K = "P4K"


class FaceMapping(BaseModel):
    sourceFaceIndex: int = Field(ge=0, le=11)
    replacementIndex: int = Field(ge=0, le=11)


class ProcessingJobRequest(BaseModel):
    projectId: str = Field(min_length=1, max_length=128)
    mediaType: MediaType
    imageFormat: ImageFormat
    quality: ExportQuality
    videoResolution: VideoResolution
    fps: int = Field(ge=1, le=120)
    multiFace: bool = False
    faceMappings: list[FaceMapping] = Field(default_factory=list, max_length=12)
    videoTrimStartMs: int = Field(default=0, ge=0)
    videoTrimEndMs: int = Field(default=0, ge=0)
    visibleWatermark: bool = True
    metadataWatermark: bool = True
    userOwnershipDeclared: bool
    termsAccepted: bool


class ProcessingJobResponse(BaseModel):
    projectId: str
    status: str
    progress: int
    statusMessage: str
    resultUrl: str | None = None
    signedDownloadUrl: str | None = None


class SourceFacePreview(BaseModel):
    sourceFaceIndex: int
    bbox: list[int]
    confidence: float
    previewDataUrl: str


class FaceDetectionResponse(BaseModel):
    mediaType: MediaType
    faces: list[SourceFacePreview]


class ContentReportRequest(BaseModel):
    projectId: str | None = None
    reason: str = Field(min_length=1, max_length=120)
    detail: str = Field(default="", max_length=2000)
