# Technical Baseline — September 2026

Bu dosya yalnızca başlangıç referansıdır. Proje oluşturulurken Android Studio'nun stable template'i ve stable dependencies yeniden kontrol edilmelidir.

## Önerilen baseline

- Android Studio: current stable
- Android Gradle Plugin: 9.4.x stable line
- JDK: 17
- Kotlin: Android Studio / AGP stable uyumlu sürüm
- Jetpack Compose: stable
- Material 3: stable
- Room: stable
- WorkManager: stable
- Navigation: stable Compose-compatible
- compileSdk/targetSdk: güncel Play/şirket dağıtım politikasına uygun

## İlke

Version numaralarını sırf "en yeni" diye alpha/beta'ya taşımayın.
Saha uygulamasında kararlılık yeni özellikten daha önemlidir.

## Uygulama yaklaşımı

- Native Android
- Kotlin
- Compose
- Feature-based modules/packages
- Offline-first
- Repository pattern
- Room as local source of truth
- WorkManager durable sync
- Retrofit/OkHttp
- DataStore
- Keystore-protected credentials/tokens
- Camera / Android Photo Picker
- FCM when push notification sprint starts
