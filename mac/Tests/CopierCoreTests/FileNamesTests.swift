import Foundation
import Testing
@testable import CopierCore

@Suite struct FileNamesTests {
    @Test(arguments: [
        ("photo.jpg", "photo.jpg"),
        ("../../etc/passwd", "_.._etc_passwd"),
        (".hidden", "hidden"),
        ("a\u{0}b\nc.txt", "a_b_c.txt"),
        ("C:\\Windows\\evil.dll", "C__Windows_evil.dll"),
        ("   ", "file"),
        ("...", "file"),
    ])
    func sanitizes(input: String, expected: String) {
        #expect(FileNames.sanitize(input) == expected)
    }

    @Test func truncatesLongNamesKeepingExtension() {
        let name = FileNames.sanitize(String(repeating: "é", count: 300) + ".pdf")
        #expect(name.utf8.count <= 200)
        #expect(name.hasSuffix(".pdf"))
    }

    @Test func uniqueURLSkipsExistingNames() {
        let existing: Set<String> = ["a.txt", "a (1).txt"]
        let url = FileNames.uniqueURL(in: URL(fileURLWithPath: "/tmp"), name: "a.txt") {
            existing.contains($0.lastPathComponent)
        }
        #expect(url.lastPathComponent == "a (2).txt")
    }

    @Test func uniqueURLHandlesNamesWithoutExtension() {
        let url = FileNames.uniqueURL(in: URL(fileURLWithPath: "/tmp"), name: "README") { $0.lastPathComponent == "README" }
        #expect(url.lastPathComponent == "README (1)")
    }
}
