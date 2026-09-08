import SwiftUI
import UIKit
import TraciveSample

/// The whole app: one Compose screen, plus the deep-link plumbing the OAuth flow needs.
///
/// `SampleController` is Kotlin. It owns the view model, so the view model outlives any single
/// SwiftUI view — which matters because the redirect can arrive at a cold start, before the first
/// frame has been drawn.
@main
struct TraciveSampleApp: App {

  private let controller = SampleController()

  var body: some Scene {
    WindowGroup {
      ComposeScreen(controller: controller)
        .ignoresSafeArea()
        // Every incoming URL goes to the SDK. It answers null for anything that is not its own
        // redirect, which is what makes routing all of them safe — and an app with other deep
        // links keeps handling them after this call rather than instead of it.
        .onOpenURL { url in
          controller.handleRedirect(url: url.absoluteString)
        }
    }
  }
}

/// Hosts the Compose `UIViewController`.
private struct ComposeScreen: UIViewControllerRepresentable {

  let controller: SampleController

  func makeUIViewController(context: Context) -> UIViewController {
    controller.viewController()
  }

  // Nothing to do: Compose drives its own state from the Kotlin view model, so there is no SwiftUI
  // state for it to read back.
  func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
