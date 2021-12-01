# Resaca 🍹
Scoped View Models for Android &amp; Compose

The goal of this project is to provide a simple way to retain a Jetpack ViewModel (or any other object) in memory in the scope of a Composable function during configuration changes and also when the container Fragment goes into the backstack.

# Why
Compose allows the creation of fine grained UI components that can be easily reused like Lego pieces 🧱. Well architectured Android apps isolate functionality in small business logic components (think of use cases, interactors, repositories, etc.) that are also reusable like Lego pieces 🧱.

Screens are built using Compose components together with business logic components but unfortunately, the standard tool to connect these components is a Jetpack ViewModel but unfortunately we can only have them scoped to a whole screen.

In practice these means that we are sticking UI Lego blocks 🧱 with business logic Lego blocks 🧱 using a big glue class, the ViewModel 🗜. Until now...

# Usage 
Inside your `@Composable` function create and retrieve an object using the `rememberScoped` (to remember any type of object) or `rememberScopedViewModel` (to remember a Jetpack ViewModel of type `ScopedViewModel`). That's all 🪄✨

Example
```kotlin
@Composable
fun DemoScopedObjectComposable() {
    val fakeRepo: FakeRepo = rememberScoped { FakeRepo() }
    DemoComposable(inputObject = fakeRepo)
}

@Composable
fun DemoScopedViewModelComposable() {
    val fakeScopedVM: FakeScopedViewModel = rememberScopedViewModel { FakeScopedViewModel() }
    DemoComposable(inputObject = fakeScopedVM)
}
```

Once you use one of the `rememberScoped` functions the same object will be restored as long as the Composable is still part of the composition, even if it temporarely leaves composition on configuration change or while being in the backstack.

# Installation
Only the three files contained in the resaca module under the package `com.sebaslogen.resaca` are required.

# Lifecycle
The `rememberScoped` functions will retain objects longer than the `remember` function but shorter than `rememberSaveable` because they store these objects in memory (no serialization involved).

This project uses a ViewModel as a container to store all scoped ViewModels and scoped objects.

When a Composable is disposed we don't know for sure if it will return again later. So at the moment of disposal we mark in our container the scoped associated object to be disposed after a small delay (currently 5 seconds). During this span of time a few things can happen:
- The Composable is not part of the composition anymore after the delay and the associated object is disposed. 🚮
- The LifecycleOwner of the disposed Composable (i.e. the navigation destination where the Composable lived) is paused before the delay finishes. Then the disposal of the scoped object is cancelled, but the object is still marked for disposal at a later stage.
  - This can happen when the application goes through a configuration change and the container Activity is recreated.
  - This can also happen when the Composable is part of a Fragment that has been pushed to the backstack.
  - When the LifecycleOwner of the disposed Composable returns to the foreground (i.e. it is resumed), then the disposal of the associated object is scheduled again to happen after a small delay. At this point two things can happen:
    - The Composable becomes part of the composition again and the `rememberScoped` function restores the associated object while also cancelling any pending delayed disposal. 🎉
    - The Composable is not part of the composition anymore after the delay and the associated object is disposed. 🚮
