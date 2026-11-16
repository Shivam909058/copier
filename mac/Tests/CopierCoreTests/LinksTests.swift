import Foundation
import Testing
@testable import CopierCore

@Suite struct LinksTests {
    @Test(arguments: [
        ("https://example.com/a?b=1", "https://example.com/a?b=1"),
        ("  http://localhost:3000/login  ", "http://localhost:3000/login"),
        ("localhost:5173", "http://localhost:5173"),
        ("127.0.0.1:8000/docs", "http://127.0.0.1:8000/docs"),
    ])
    func parsesWebLinks(input: String, expected: String) {
        #expect(Links.webURL(from: input)?.absoluteString == expected)
    }

    @Test(arguments: ["hello world", "ftp://example.com", "javascript:alert(1)", "file:///etc/passwd", "", "two\nlines"])
    func rejectsNonWebText(input: String) {
        #expect(Links.webURL(from: input) == nil)
    }

    @Test func rewritesLoopbackHostsForThePhone() throws {
        let rewritten = Links.forPhone(try #require(URL(string: "http://localhost:3000/a?b=1#c")), lanHost: "192.168.1.5")
        #expect(rewritten.absoluteString == "http://192.168.1.5:3000/a?b=1#c")
        #expect(Links.forPhone(try #require(URL(string: "http://0.0.0.0:8080")), lanHost: "10.0.0.2").host == "10.0.0.2")
    }

    @Test func leavesOtherLinksAlone() throws {
        let url = try #require(URL(string: "https://github.com/x"))
        #expect(Links.forPhone(url, lanHost: "192.168.1.5") == url)
        #expect(Links.forPhone(try #require(URL(string: "http://localhost:3000")), lanHost: nil).host == "localhost")
    }
}

@Suite struct OneTimeCodeTests {
    @Test(arguments: [
        ("482913 is your Instagram code. Don't share it.", "482913"),
        ("Your OTP for login is 839201. Valid for 10 mins.", "839201"),
        ("G-123456 is your Google verification code.", "123456"),
        ("Your Uber code is 123-456", "123456"),
        ("₹5,000 debited from A/c XX1234. OTP 5521 for this txn.", "5521"),
        ("Your verification code is 4821. Call 9876543210 for help.", "4821"),
        ("Use 12345678 to sign in to Contoso", "12345678"),
    ])
    func findsCodes(text: String, expected: String) {
        #expect(OneTimeCode.find(in: text) == expected)
    }

    @Test(arguments: [
        "Meeting moved to 10:30 tomorrow",
        "Order 1234567 has shipped",
        "Your login code: 2026-11-16 expires",
        "Rs.4500 paid. Your security is our priority",
        "Battery at 85% — verification pending",
    ])
    func ignoresNonCodes(text: String) {
        #expect(OneTimeCode.find(in: text) == nil)
    }
}
