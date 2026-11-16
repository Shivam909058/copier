// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Copier",
    platforms: [.macOS(.v14)],
    targets: [
        .target(name: "CopierCore"),
        .executableTarget(name: "Copier", dependencies: ["CopierCore"]),
        // Not "copier": the default macOS filesystem is case-insensitive and would collide with "Copier".
        .executableTarget(name: "copier-cli", dependencies: ["CopierCore"]),
        .testTarget(name: "CopierCoreTests", dependencies: ["CopierCore"]),
        .testTarget(name: "CopierTests", dependencies: ["Copier", "CopierCore"]),
    ]
)
