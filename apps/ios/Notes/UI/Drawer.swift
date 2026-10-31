import SwiftUI
import UIKit

/// Whether the sidebar is out. Shared through the environment, so the list's
/// button can open it and the sidebar's rows can close it.
@MainActor
@Observable
final class Drawer {
    var isOpen = false

    func open() { set(true) }
    func close() { set(false) }

    func set(_ open: Bool) {
        withAnimation(Drawer.motion) { isOpen = open }
    }

    /// Fast to start, settling without a bounce: it follows the finger, then lands.
    static var motion: Animation {
        UIAccessibility.isReduceMotionEnabled ? .easeOut(duration: 0.2) : .spring(response: 0.34, dampingFraction: 0.9)
    }
}

/// The sidebar slides in from the left, pushing the content aside; the
/// content dims and stays a tap away. Opened with a button or by swiping in
/// from the left edge (while `swipeToOpen`); swiping back or tapping the
/// content closes it. Both follow the finger.
struct DrawerContainer<Content: View, Side: View>: View {
    @Bindable var drawer: Drawer
    var swipeToOpen: Bool
    @ViewBuilder var content: Content
    @ViewBuilder var side: Side

    /// How far the finger has moved the drawer from where it rests.
    @State private var drag: CGFloat = 0
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        GeometryReader { geo in
            let width = min(geo.size.width * 0.84, 360)
            let progress = min(max((drawer.isOpen ? 1 : 0) + drag / width, 0), 1)
            ZStack(alignment: .leading) {
                side
                    // The container reaches under the bars; the sidebar's own content stays clear of them.
                    .safeAreaPadding(.top, geo.safeAreaInsets.top)
                    .safeAreaPadding(.bottom, geo.safeAreaInsets.bottom)
                    .frame(width: width)
                    .offset(x: (progress - 1) * width * 0.3)
                    .opacity(0.3 + 0.7 * progress)
                    .simultaneousGesture(closeDrag(width), isEnabled: drawer.isOpen)
                    .accessibilityElement(children: .contain)
                    .accessibilityLabel("Sidebar")
                    .accessibilityHidden(!drawer.isOpen)
                    // VoiceOver's scrub gesture closes it.
                    .accessibilityAction(.escape) { drawer.close() }

                content
                    .overlay {
                        if progress > 0 {
                            Color.black.opacity((scheme == .dark ? 0.5 : 0.22) * progress)
                                .ignoresSafeArea()
                                .contentShape(.rect)
                                .onTapGesture { drawer.close() }
                                .gesture(closeDrag(width))
                                .accessibilityLabel("Close sidebar")
                                .accessibilityAddTraits(.isButton)
                                .accessibilityAction { drawer.close() }
                        }
                    }
                    // Not clipped to round its corners: a clip hides the Liquid Glass of a vertical bar
                    // (the iPhone Duo's outer display), leaving its buttons there but not drawn.
                    .shadow(color: .black.opacity(0.28 * progress), radius: 24)
                    .offset(x: progress * width)
                    .gesture(
                        EdgePan(enabled: swipeToOpen && !drawer.isOpen) { dx in
                            drag = max(dx, 0)
                        } ended: { dx, velocity in
                            settle(open: dx + velocity * 0.2 > width * 0.4, width: width)
                        }
                    )
                    .accessibilityHidden(drawer.isOpen)
            }
            .ignoresSafeArea(.container, edges: .vertical)
        }
        .ignoresSafeArea(.keyboard)
    }

    /// A sideways drag that closes the drawer; vertical ones are left to scrolling.
    private func closeDrag(_ width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { value in
                let dx = value.translation.width
                guard abs(dx) > abs(value.translation.height) || drag != 0 else { return }
                drag = min(dx, 0)
            }
            .onEnded { value in
                guard drag != 0 else { return }
                let projected = value.predictedEndTranslation.width
                settle(open: !(drag < -width * 0.3 || projected < -width * 0.5), width: width)
            }
    }

    private func settle(open: Bool, width: CGFloat) {
        withAnimation(Drawer.motion) {
            drawer.isOpen = open
            drag = 0
        }
    }
}

/// A swipe in from the screen's left edge, as UIKit recognises it: it doesn't
/// take taps or scrolling from what's under it.
private struct EdgePan: UIGestureRecognizerRepresentable {
    var enabled: Bool
    var changed: (CGFloat) -> Void
    var ended: (_ dx: CGFloat, _ velocity: CGFloat) -> Void

    func makeUIGestureRecognizer(context: Context) -> UIScreenEdgePanGestureRecognizer {
        let pan = UIScreenEdgePanGestureRecognizer()
        pan.edges = .left
        return pan
    }

    func updateUIGestureRecognizer(_ pan: UIScreenEdgePanGestureRecognizer, context: Context) {
        pan.isEnabled = enabled
    }

    func handleUIGestureRecognizerAction(_ pan: UIScreenEdgePanGestureRecognizer, context: Context) {
        let dx = pan.translation(in: pan.view).x
        switch pan.state {
        case .began, .changed: changed(dx)
        case .ended: ended(dx, pan.velocity(in: pan.view).x)
        case .cancelled, .failed: ended(0, 0)
        default: break
        }
    }
}
