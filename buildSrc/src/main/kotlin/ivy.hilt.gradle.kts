plugins {
    id("ivy.kotlin-android")
    id("dagger.hilt.android.plugin")
    id("com.google.devtools.ksp")
}

kotlin {
    sourceSets.all {
        kotlin.srcDir("build/generated/ksp/$name/kotlin")
    }
}

dependencies {
    implementation(libs.bundles.hilt)
    // Dagger's processor: @Inject constructors, @HiltViewModel, @AndroidEntryPoint, ...
    ksp(catalog.library("hilt-compiler"))
    // androidx's processor: it is the ONLY thing that turns `@HiltWorker` into something the
    // `HiltWorkerFactory` can build (the `<Worker>_AssistedFactory` + its binding in the
    // WorkManager factory map). Without it that map has no entry for the worker, so
    // `HiltWorkerFactory.createWorker()` returns null and WorkManager silently falls back to
    // reflectively calling `(Context, WorkerParameters)` - a constructor an `@AssistedInject`
    // worker does not have. The scheduled worker then never runs.
    ksp(catalog.library("androidx-hilt-compiler"))
}
