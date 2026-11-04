package com.gamixtv.MotionMagicAI.data

import com.gamixtv.MotionMagicAI.cloud.CloudJob

enum class ProjectFilter(val label: String) { ALL("All"), IMAGES("Images"), VIDEOS("Videos"), VOICE("Voice") }

sealed class LibraryItem {
    data class Device(val project: Project) : LibraryItem()
    data class Cloud(val job: CloudJob) : LibraryItem()

    val key: String get() = when(this) { is Device -> project.id; is Cloud -> "cloud_${job.id}" }
    val title: String get() = when(this) { is Device -> project.title; is Cloud -> job.title }
    val createdAt: Long get() = when(this) { is Device -> project.createdAt; is Cloud -> job.createdAt }
    val kind: MediaKind get() = when(this) { is Device -> project.kind; is Cloud -> if(job.kind == "image") MediaKind.IMAGE else MediaKind.VIDEO }
    fun matches(filter: ProjectFilter, query: String) = title.contains(query.trim(), ignoreCase = true) && when(filter) {
        ProjectFilter.ALL -> true
        ProjectFilter.IMAGES -> kind == MediaKind.IMAGE
        ProjectFilter.VIDEOS -> kind == MediaKind.VIDEO
        ProjectFilter.VOICE -> kind == MediaKind.AUDIO
    }
}

/** Prefer an available device copy; otherwise keep the cloud result as the single entry. */
fun projectLibrary(projects: List<Project>, jobs: List<CloudJob>, availableLocalIds: Set<String>): List<LibraryItem> {
    // Use explicit values rather than sharing mutable UI state between accounts.
    val merged = linkedMapOf<String, LibraryItem>()
    jobs.forEach { merged["cloud_${it.id}"] = LibraryItem.Cloud(it) }
    projects.forEach { project ->
        if(project.id !in merged || project.id in availableLocalIds) merged[project.id] = LibraryItem.Device(project)
    }
    return merged.values.sortedWith(compareByDescending<LibraryItem> { it.createdAt }.thenByDescending { it.key })
}
