package com.ivy.navigation

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

@SuppressLint("ComposeCompositionLocalUsage")
private val LocalNavigation = compositionLocalOf<Navigation> { error("No LocalNavigation") }

/**
 * A ViewModel store that belongs to exactly one screen.
 *
 * The store is created together with the screen and cleared when the screen goes away, so a
 * navigation can only ever destroy the ViewModels of the screen that is being left - never the
 * ViewModels of the screen that is opening. Clearing the host's store from a disposal callback
 * cannot promise that: disposals run after the next screen has already composed and created its
 * ViewModel, so the new screen used to be killed in the middle of its first load and rendered
 * empty until it was opened a second time.
 */
private class ScreenViewModelStoreOwner(
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory,
    override val defaultViewModelCreationExtras: CreationExtras,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

@Composable
fun NavigationRoot(
    navigation: Navigation,
    navGraph: @Composable (screen: Screen?) -> Unit
) {
    CompositionLocalProvider(
        LocalNavigation provides navigation,
    ) {
        val screen = navigation.currentScreen
        if (screen?.isLegacy != false) {
            // Legacy screens keep using the host's store, exactly as before.
            navGraph(screen)
        } else {
            // Screen-scoped ViewModels are @HiltViewModel classes with @Inject constructors. A bare
            // store owner has no factory, so ViewModelProvider fell back to the no-arg constructor
            // and crashed ("Cannot create an instance of class ...ViewModel"). The host's factory
            // (Hilt's) and creation extras are borrowed; only the store itself is screen-bound.
            val hostFactory = LocalViewModelStoreOwner.current as? HasDefaultViewModelProviderFactory
            val screenStore = remember(screen, hostFactory) {
                ScreenViewModelStoreOwner(
                    defaultViewModelProviderFactory = hostFactory?.defaultViewModelProviderFactory
                        ?: ViewModelProvider.NewInstanceFactory(),
                    defaultViewModelCreationExtras = hostFactory?.defaultViewModelCreationExtras
                        ?: CreationExtras.Empty,
                )
            }
            CompositionLocalProvider(LocalViewModelStoreOwner provides screenStore) {
                navGraph(screen)
            }
            DisposableEffect(screenStore) {
                onDispose { screenStore.viewModelStore.clear() }
            }
        }
    }
}

@Composable
fun navigation(): Navigation {
    return LocalNavigation.current
}

/**
 * Provides a [ViewModel] instance scoped the screen's life.
 * When the user navigates away from the screen all screen scoped
 * viewModels are destroyed.
 * Does not apply for legacy screens.
 */
@Composable
inline fun <reified T : ViewModel> screenScopedViewModel(
    factory: ViewModelProvider.Factory? = null
): T {
    val viewModelStoreOwner = LocalViewModelStoreOwner.current
    requireNotNull(viewModelStoreOwner) { "No ViewModelStoreOwner provided" }
    val viewModelProvider = factory?.let {
        ViewModelProvider(viewModelStoreOwner, it)
    } ?: ViewModelProvider(viewModelStoreOwner)
    return viewModelProvider[T::class.java]
}
