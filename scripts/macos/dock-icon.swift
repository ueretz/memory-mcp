import AppKit

let size: CGFloat = 1024
let output = CommandLine.arguments[1]

let image = NSImage(size: NSSize(width: size, height: size))
image.lockFocus()

// Same inset and corner radius as the macOS app icon grid, so the tile lines up with neighbours in the Dock.
let inset: CGFloat = 100
let tileRect = NSRect(x: inset, y: inset, width: size - 2 * inset, height: size - 2 * inset)
let tile = NSBezierPath(roundedRect: tileRect, xRadius: 185, yRadius: 185)
NSGradient(starting: NSColor(calibratedRed: 0.36, green: 0.30, blue: 0.85, alpha: 1),
           ending: NSColor(calibratedRed: 0.14, green: 0.11, blue: 0.40, alpha: 1))!
    .draw(in: tile, angle: -90)

let glyph = "🧠" as NSString
let attributes: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 520)]
let glyphSize = glyph.size(withAttributes: attributes)
glyph.draw(at: NSPoint(x: (size - glyphSize.width) / 2, y: (size - glyphSize.height) / 2 + 10),
           withAttributes: attributes)

image.unlockFocus()

guard let tiff = image.tiffRepresentation,
      let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) else {
    FileHandle.standardError.write("Failed to render the Dock icon\n".data(using: .utf8)!)
    exit(1)
}
try png.write(to: URL(fileURLWithPath: output))
