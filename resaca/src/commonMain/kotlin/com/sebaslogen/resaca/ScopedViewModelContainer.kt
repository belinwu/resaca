package com.sebaslogen.resaca

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.Immutable
import androidx.core.bundle.Bundle
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.sebaslogen.resaca.utils.ResacaPackagePrivate
import com.sebaslogen.resaca.utils.getCanonicalNameKey
import com.sebaslogen.resaca.viewmodel.DefaultViewModelProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.set
import kotlin.jvm.JvmInline
import kotlin.reflect.KClass


internal const val MISSING_VIEW_MODEL_STORE_OWNER = "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"

/**
 * [ViewModel] class used to store objects and [ViewModel]s as long as the
 * requester doesn't completely leave composition (even temporary)
 * or the scope of this [ViewModel] is cleared.
 *
 * ************************
 * * Composable lifecycle *
 * ************************
 *
 * The lifecycle of a Composable doesn't match the one from this ViewModel nor Activities/Fragments, it's alive
 * as long it's part of the composition and in some cases even after temporary leaving composition.
 *
 * In Compose, we don't know for sure at the moment of disposal if the
 * Composable will be disposed for good or if it will return again later.
 * Therefore, at the moment of disposal, we mark in our container the scoped
 * associated object to be disposed after the next frame when the Activity is resumed.
 * During the span of time of this next frame a few things can happen:
 * - The Composable is not part of the composition anymore after the next frame and the associated object is disposed.
 * - The [LifecycleOwner] of the disposed Composable (i.e. the navigation destination where the Composable lived)
 * is paused (e.g. screen went to background) before the next frame happened. Then the disposal of the scoped object is cancelled,
 * but the object is still marked for disposal at a later stage after resume of the [LifecycleOwner].
 *      * This can happen when the application goes through a configuration change and the container Activity/Fragment is recreated.
 *      * This can also happen when the Composable is part of a Fragment that has been pushed to the backstack.
 * - When the [LifecycleOwner] of the disposed Composable is resumed (i.e. screen comes back to foreground),
 * then the disposal of the associated object is scheduled again to happen after the next frame when the Activity is resumed.
 * At this point two things can happen:
 *      * The Composable becomes part of the composition again and the [rememberScoped] function restores
 *      the associated object while also cancelling any pending disposal in the next frame when the Activity is resumed.
 *      * The Composable is not part of the composition anymore after the next frame and the associated object is disposed.
 *
 *
 * To detect when the requester Composable is not needed anymore (has left composition and
 * the screen for good), this class observes
 * - the [Lifecycle] of the owner of this [ScopedViewModelContainer] (i.e. Activity, Fragment or Compose Navigation destination)
 * - the foreground/background state of the container [LifecycleOwner]
 * - the configuration changing state of the container [Activity]
 */
@ResacaPackagePrivate
public class ScopedViewModelContainer : ViewModel(), LifecycleEventObserver {

    /**
     * Handler to manage the lifecycle of the container of this class (usually a screen like an Activity, a Fragment or a Compose destination)
     * Since the container of this class is different per platform, this handler abstracts the platform-specific lifecycle handling
     */
    private val platformLifecycleHandler: PlatformLifecycleHandler = PlatformLifecycleHandler()

    /**
     * Mark whether the container of this class (usually a screen like an Activity, a Fragment or a Compose destination)
     * is in foreground or in background to avoid disposing objects while in the background
     */
    private var isInForeground = false

    /**
     * This callback will let the platform specific lifecycle handler code indicate
     * whether the container of this class (e.g. a NavHost inside an Activity) has
     * finished changing configuration and is returning to foreground.
     *
     * This is useful to dispose objects that were marked for disposal after the container
     * went to background and there was no callback to retrieve the object again after coming
     * back to foreground (e.g. during a configuration change). In this case,
     * we are sure the object is not needed anymore and can be safely disposed of.
     *
     * Note: This is only required when no other object scoped to this container is still alive, otherwise,
     * the [isInForeground] will be used to dispose objects that are completely gone after a configuration change.
     */
    private var isReturningToForeground: () -> Boolean = { false }

    /**
     * Container of object keys associated with their [ExternalKey],
     * the [ExternalKey] will be used to track and store new versions of the object to be stored/restored
     */
    private val scopedObjectKeys: MutableMap<InternalKey, ExternalKey> = mutableMapOf()

    /**
     * Generic objects container
     */
    private val scopedObjectsContainer: MutableMap<InternalKey, Any> = mutableMapOf()

    /**
     * List of [Job]s associated with an object (through its key) that is scheduled to be disposed very soon, unless
     * the object is requested again (and [cancelDisposal] is triggered) or
     * the container of this [ScopedViewModelContainer] class goes to the background (making [isInForeground] false)
     */
    private val disposingJobs: MutableMap<InternalKey, Job> = mutableMapOf()

    /**
     * List of keys for the objects that will be disposed (forgotten from this class so they can be garbage collected) in the near future
     */
    private val markedForDisposal: MutableSet<InternalKey> = mutableSetOf()

    /**
     * Launch a coroutine in the [viewModelScope] that will execute [block] on the same thread.
     * The [Dispatchers.Main.immediate] dispatcher will be used to avoid unnecessary thread jumps.
     * If [Dispatchers.Main.immediate] is not available in the target platform, then [Dispatchers.Main] will be used.
     */
    private fun threadSafeRunnerOnMain(block: suspend () -> Unit): Job {
        val dispatcher: MainCoroutineDispatcher =
            try {
                Dispatchers.Main.immediate
            } catch (e: UnsupportedOperationException) { // When Main.immediate is not available in the target platform
                Dispatchers.Main
            }
        return viewModelScope.launch(dispatcher + NonCancellable) { // This needs to be non-cancellable to avoid cancellation of the block in onCleared
            block()
        }
    }

    /**
     * Restore or build an object of type [T] using the provided [builder] as the factory
     */
    @Suppress("UNCHECKED_CAST")
    @Composable
    internal fun <T : Any> getOrBuildObject(
        positionalMemoizationKey: InternalKey,
        externalKey: ExternalKey,
        builder: @DisallowComposableCalls () -> T
    ): T {
        @Composable
        fun buildAndStoreObject() = builder.invoke().apply { scopedObjectsContainer[positionalMemoizationKey] = this }
        cancelDisposal(positionalMemoizationKey)

        val originalObject: Any? = scopedObjectsContainer[positionalMemoizationKey]
        return if (scopedObjectKeys.containsKey(positionalMemoizationKey) && (scopedObjectKeys[positionalMemoizationKey] == externalKey)) {
            // When the object is already present and the external key matches, then try to restore it
            originalObject as? T ?: buildAndStoreObject()
        } else { // First time object creation or externalKey changed
            scopedObjectKeys[positionalMemoizationKey] = externalKey // Set the external key used to track and store the new object version
            scopedObjectsContainer.remove(positionalMemoizationKey) // Remove in case key changed
                ?.also { clearLastDisposedObject(it) } // Old object may need to be cleared before it's forgotten
            buildAndStoreObject()
        }
    }

    /**
     * Restore or build a [ViewModel] using the default factory for ViewModels without constructor parameters
     */
    @Composable
    public fun <T : ViewModel> getOrBuildViewModel(
        modelClass: KClass<T>,
        positionalMemoizationKey: InternalKey,
        externalKey: ExternalKey,
        defaultArguments: Bundle
    ): T {
        val owner = checkNotNull(LocalViewModelStoreOwner.current) { MISSING_VIEW_MODEL_STORE_OWNER }
        val factory = if (owner is HasDefaultViewModelProviderFactory) owner.defaultViewModelProviderFactory else DefaultViewModelProviderFactory
        return getOrBuildViewModel(
            modelClass = modelClass,
            positionalMemoizationKey = positionalMemoizationKey,
            externalKey = externalKey,
            factory = factory,
            defaultArguments = defaultArguments,
            viewModelStoreOwner = owner
        )
    }

    /**
     * Restore or build a [ViewModel] using a factory provided or the default factory if none is provided
     */
    @Composable
    public fun <T : ViewModel> getOrBuildViewModel(
        modelClass: KClass<T>,
        positionalMemoizationKey: InternalKey,
        externalKey: ExternalKey,
        factory: ViewModelProvider.Factory?,
        defaultArguments: Bundle,
        viewModelStoreOwner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) { MISSING_VIEW_MODEL_STORE_OWNER }
    ): T = getOrBuildViewModel(
        modelClass = modelClass,
        positionalMemoizationKey = positionalMemoizationKey,
        externalKey = externalKey,
        factory = factory,
        creationExtras = defaultArguments.toCreationExtras(viewModelStoreOwner),
        viewModelStoreOwner = viewModelStoreOwner
    )

    /**
     * Restore or build a [ViewModel] using the provided [builder] as the factory
     */
    @Composable
    public fun <T : ViewModel> getOrBuildViewModel(
        modelClass: KClass<T>,
        positionalMemoizationKey: InternalKey,
        externalKey: ExternalKey,
        defaultArguments: Bundle,
        builder: @DisallowComposableCalls (savedStateHandle: SavedStateHandle) -> T
    ): T {
        val viewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) { MISSING_VIEW_MODEL_STORE_OWNER }
        val viewModelKey = modelClass.getCanonicalNameKey(positionalMemoizationKey + externalKey)
        val creationExtrasWithViewModelKey = defaultArguments.toCreationExtras(viewModelStoreOwner).addViewModelKey(viewModelKey)
        val savedStateHandle: SavedStateHandle = creationExtrasWithViewModelKey.createSavedStateHandle()
        return getOrBuildViewModel(
            modelClass = modelClass,
            positionalMemoizationKey = positionalMemoizationKey,
            externalKey = externalKey,
            factory = ScopedViewModelOwner.viewModelFactoryFor(savedStateHandle, builder),
            creationExtras = creationExtrasWithViewModelKey,
            viewModelStoreOwner = viewModelStoreOwner
        )
    }

    /**
     * Restore or build a [ViewModel] using a factory provided or the default factory if none is provided
     */
    @Composable
    public fun <T : ViewModel> getOrBuildViewModel(
        modelClass: KClass<T>,
        positionalMemoizationKey: InternalKey,
        externalKey: ExternalKey,
        factory: ViewModelProvider.Factory?,
        creationExtras: CreationExtras,
        viewModelStoreOwner: ViewModelStoreOwner
    ): T = ScopedViewModelUtils.getOrBuildViewModel(
        modelClass = modelClass,
        positionalMemoizationKey = positionalMemoizationKey,
        externalKey = externalKey,
        factory = factory,
        viewModelStoreOwner = viewModelStoreOwner,
        creationExtras = creationExtras,
        scopedObjectsContainer = scopedObjectsContainer,
        scopedObjectKeys = scopedObjectKeys,
        cancelDisposal = ::cancelDisposal,
        clearLastDisposedViewModel = ::clearLastDisposedObject
    )

    /**
     * Triggered when a Composable that stored an object in this class is disposed and signals this container
     * that the object might be also disposed from this container only when the stored object
     * is not going to be used anymore (e.g. after configuration change or container fragment returning from backstack)
     */
    internal fun onRemovedFromComposition(key: InternalKey) {
        threadSafeRunnerOnMain {
            markedForDisposal.add(key) // Marked to be disposed after onResume
            scheduleToDisposeBeforeGoingToBackground(key) // Schedule to dispose this object before onPause
        }
    }

    /**
     * Triggered when the Composable that created the [KeyInScopeResolver] is disposed and signals this container
     * that the [KeyInScopeResolver] is not in scope anymore so all the [ExternalKey]s contained in the [KeyInScopeResolver]
     * need to be disposed of.
     */
    internal fun <T> onRemovedFromComposition(keyInScopeResolver: KeyInScopeResolver<T>) {
        threadSafeRunnerOnMain {
            val keys = scopedObjectKeys.toList()
            keys.forEach { (key, externalKey: ExternalKey) ->
                val scopeKeyWithResolver: ScopeKeyWithResolver<*>? = externalKey.scopeKeyWithResolver() // Get the KeyInScopeResolver if ExternalKey is one
                if (scopeKeyWithResolver is ScopeKeyWithResolver && scopeKeyWithResolver.keyInScopeResolver == keyInScopeResolver) {
                    onRemovedFromComposition(key = key) // Mark it to be disposed if the disposed KeyInScopeResolver matches
                }
            }
        }
    }

    /**
     * Schedules the object referenced by this [key] in the [scopedObjectsContainer]
     * to be removed (so it can be garbage collected) if the screen associated with this is still in foreground ([isInForeground])
     */
    private suspend fun scheduleToDisposeBeforeGoingToBackground(key: InternalKey) {
        scheduleToDispose(key = key)
    }

    /**
     * Dispose item from [scopedObjectsContainer] after the screen resumes
     * This makes possible to garbage collect objects that were disposed by Compose right before the container screen went
     * to background ([Lifecycle.Event.ON_PAUSE]) and are not required anymore whe the screen is back in foreground ([Lifecycle.Event.ON_RESUME])
     *
     * Postponed disposal: Instead of immediately disposing the object after [Lifecycle.Event.ON_RESUME], launch a coroutine that awaits
     * for the first frame to give the Activity/Fragment enough time to recreate the desired UI after resuming or configuration change.
     * Upon disposal, [ViewModel] objects will also be requested to cancel all their coroutines in their [CoroutineScope].
     */
    private suspend fun scheduleToDisposeAfterReturningFromBackground() {
        markedForDisposal.toList().forEach { key ->
            scheduleToDispose(key)
        }
    }

    /**
     * Dispose/Forget the object -if present- in [scopedObjectsContainer] but wait for the next frame when the Activity is resumed if UI is not in foreground.
     * Additionally, if the object is not in scope anymore but its [ExternalKey] is an [ScopeKeyWithResolver], then we check if the [ExternalKey]
     * is still in scope before disposing the object. This allows the object to be disposed only when the [ExternalKey]
     * is not in scope anymore and it was already disposed of the Compose scope.
     * We store the deletion job with the given [key] in the [disposingJobs] to make sure we don't schedule the same work twice.
     *
     * @param key Key of the object stored in either [scopedObjectsContainer] to be de-referenced for GC
     *
     * By default we want to remove only when:
     * - scope is in the foreground, because if it's in the background it might be needed again when returning to foreground,
     * in that case the decision will be deferred to [scheduleToDisposeAfterReturningFromBackground]
     * - or when recreating due configuration changes, because after a configuration change, the [cancelDisposal] should have been
     * called once the scoped object was requested again, the fact that this is still scheduled after the first frame means
     * it's not needed after the configuration change.
     *      Note: [isChangingConfiguration] is only required when no other object scoped to this container is still alive, otherwise,
     *      the [isInForeground] will be used to dispose objects that are completely gone after a configuration change.
     */
    private fun scheduleToDispose(key: InternalKey) {
        if (disposingJobs.containsKey(key)) return // Already disposing, quit
        val newDisposingJob = viewModelScope.launch {
            platformLifecycleHandler.awaitBeforeDisposing(isInForeground) // Android: Wait for next frame when the Activity is resumed. No-op in other platforms
            threadSafeRunnerOnMain {
                withContext(NonCancellable) { // We treat the disposal/remove/clear block as an atomic transaction
                    if (isInForeground || isReturningToForeground()) {
                        val externalKey: ExternalKey? = scopedObjectKeys[key]
                        val objectExternalKeyNotInScope = externalKey?.scopeKeyWithResolver()?.isKeyInScope() != true
                        if (objectExternalKeyNotInScope) { // If the key is not in scope, then the object is not needed anymore
                            markedForDisposal.remove(key)
                            scopedObjectKeys.remove(key)
                            scopedObjectsContainer.remove(key)?.also { clearLastDisposedObject(it) }
                        }
                    }
                    disposingJobs.remove(key)
                }
            }
        }
        disposingJobs[key] = newDisposingJob
    }

    /**
     * An object that is being disposed should also be cleared only if it was the last instance present in this container
     */
    private fun clearLastDisposedObject(disposedObject: Any, objectsContainer: List<Any> = scopedObjectsContainer.values.toList()) {
        threadSafeRunnerOnMain {
            ScopedViewModelUtils.clearLastDisposedObject(disposedObject, objectsContainer)
        }
    }

    private fun cancelDisposal(key: InternalKey) {
        threadSafeRunnerOnMain {
            disposingJobs.remove(key)?.cancel() // Cancel scheduled disposal
            markedForDisposal.remove(key) // Un-mark for disposal in case it's not yet scheduled for disposal
        }
    }

    /**
     * Cancel all [ViewModel] coroutines in their [CoroutineScope]
     */
    override fun onCleared() {
        // Cancel disposal jobs, all those references will be garbage collected anyway with this ViewModel
        disposingJobs.values.forEach { job -> job.cancel() }
        // Cancel all coroutines, Closeables and ViewModels hosted in this object
        val objectsToClear: MutableList<Any> = scopedObjectsContainer.values.toMutableList()
        while (objectsToClear.isNotEmpty()) {
            val lastObject = objectsToClear.removeAt(objectsToClear.lastIndex)
            clearLastDisposedObject(lastObject, objectsToClear)
        }
        scopedObjectKeys.clear() // Clear all keys
        scopedObjectsContainer.clear() // Clear just in case this VM is leaked
        super.onCleared()
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> { // Note Fragment View creation happens before this onResume
                threadSafeRunnerOnMain {
                    isInForeground = true
                    isReturningToForeground = { false } // Reset this flag when the scope is resumed
                    platformLifecycleHandler.onResumed() // Notify the handler that the Activity is resumed
                    scheduleToDisposeAfterReturningFromBackground()
                }
            }

            Lifecycle.Event.ON_PAUSE -> {
                threadSafeRunnerOnMain {
                    isInForeground = false
                }
            }

            Lifecycle.Event.ON_DESTROY -> { // Remove ourselves so that this ViewModel can be garbage collected
                threadSafeRunnerOnMain {
                    source.lifecycle.removeObserver(this)
                    platformLifecycleHandler.onDestroyed() // Notify the handler that the Activity is destroyed
                }
            }

            else -> {
                // No-Op: the other lifecycle event are irrelevant for this class
            }
        }
    }

    /**
     * We need to track if the last object was disposed when going to background and the Activity is being recreated,
     * because, if no other scoped object in this container observes the [isInForeground] state,
     * after the next frame when the Activity is resumed, the object can safely be disposed of.
     * In this case, we can safely assume the object was never requested again
     * after the configuration change finished and the container screen was again in the foreground.
     */
    internal fun setShouldBeReturningToForeground(query: () -> Boolean) {
        isReturningToForeground = query
    }

    /**
     * Unique Key to identify versions objects stored in the [ScopedViewModelContainer]
     * When this external key does not match the one stored for an object's main key in [scopedObjectKeys],
     * then the old object is cleared, the new instance is stored (replacing old instance in storage) and
     * the new external key is stored in [scopedObjectKeys]
     */
    @Immutable
    @JvmInline
    public value class ExternalKey(private val value: Any?) {
        internal fun scopeKeyWithResolver(): ScopeKeyWithResolver<*>? = value as? ScopeKeyWithResolver<*>
    }

    @Immutable
    @JvmInline
    public value class InternalKey(public val value: String) : Comparable<InternalKey> {
        override fun compareTo(other: InternalKey): Int = value.compareTo(other.value)
    }
}