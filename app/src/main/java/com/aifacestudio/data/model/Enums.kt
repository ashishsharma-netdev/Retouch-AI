package com.aifacestudio.data.model

enum class MediaType(val title: String, val noun: String) {
    IMAGE("Image Face Replace", "Image"),
    VIDEO("Video Face Replace", "Video"),
    GIF("GIF Face Replace", "GIF")
}

enum class MediaRole {
    SOURCE,
    FACE,
    OUTPUT
}

enum class ProjectStatus {
    DRAFT,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELED
}

enum class ExportQuality(val label: String) {
    STANDARD("Standard"),
    HIGH("High"),
    ULTRA("Ultra")
}

enum class ImageFormat(val label: String, val mimeType: String, val extension: String) {
    JPG("JPG", "image/jpeg", "jpg"),
    PNG("PNG", "image/png", "png"),
    WEBP("WEBP", "image/webp", "webp")
}

enum class VideoResolution(val label: String) {
    P720("720P"),
    P1080("1080P"),
    P4K("4K")
}

enum class SubscriptionPlan(val label: String) {
    FREE("Free"),
    PREMIUM("Premium")
}

enum class ShareTarget(val label: String, val packageName: String?) {
    GENERIC("Share", null),
    WHATSAPP("WhatsApp", "com.whatsapp"),
    TELEGRAM("Telegram", "org.telegram.messenger"),
    INSTAGRAM("Instagram", "com.instagram.android"),
    FACEBOOK("Facebook", "com.facebook.katana"),
    EMAIL("Email", null)
}
