import SwiftUI

/// The note's side with no note open, beside the list (Android: NothingOpen).
struct NothingOpen: View {
    let onNew: () -> Void

    var body: some View {
        VStack(spacing: 10) {
            Text("Nothing selected").font(.notesKicker).foregroundStyle(Color.ink3)
            Text("Pick a note, or start one.")
                .font(.notesHeading)
                .foregroundStyle(Color.ink)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            Button(action: onNew) {
                Label("Write a note", systemImage: "square.and.pencil")
                    .font(.notesButton)
                    .foregroundStyle(Color(Palette.onSolid))
                    .padding(.horizontal, 18)
                    .frame(minHeight: 44)
                    .background(Color(Palette.solid), in: .capsule)
            }
            .buttonStyle(PressScaleStyle(scale: 0.95))
            .padding(.top, 14)
        }
        .frame(maxWidth: 380)
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.notesBg)
    }
}
