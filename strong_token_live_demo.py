#!/usr/bin/env python3
"""
Strong token live brute-force demo (parallel, unlimited threads)

WARNING: Use only against systems you own or have explicit permission to test.
This script generates random tokens and sends them to the real admin endpoint
in parallel to demonstrate that a strong token cannot be guessed.

Supports infinite mode (--attempts 0) that runs until Ctrl+C.
Supports any number of threads (50, 100, 1000+).

Purpose: defensive security testing for the Claude API proxy admin panel.
"""

import argparse
import secrets
import signal
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import requests

DEFAULT_URL = "https://claude-code-cli.vibecode-claude.online/manage/keys/list"
DEFAULT_TIMEOUT = 10
DEFAULT_THREADS = 10
BATCH_SIZE = 500  # submission batch size for infinite mode


def live_bruteforce(url: str, attempts: int, threads: int, timeout: int) -> bool:
    """
    Generate random tokens and send them to the real endpoint in parallel.
    attempts=0 means infinite mode (until Ctrl+C).
    Stops if a valid token is found or the server blocks us.
    Returns True if a valid token is found, False otherwise.
    """
    infinite = (attempts == 0)

    print("=" * 60)
    print("STRONG TOKEN LIVE BRUTE-FORCE DEMO (parallel)")
    print("=" * 60)
    print(f"Target URL:      {url}")
    print(f"Mode:            {'INFINITE (Ctrl+C to stop)' if infinite else f'{attempts:,} attempts'}")
    print(f"Threads:         {threads}")
    print(f"Token format:    secrets.token_urlsafe(32) — 256 bits of entropy")
    print(f"Timeout:         {timeout}s")
    print()
    print("This generates random 43-char base64url tokens and sends them")
    print("to the real endpoint. It will NOT find the real token.")
    print("Purpose: show that parallel brute-force on a strong token is futile.")
    if infinite:
        print("Press Ctrl+C at any time to stop and see statistics.")
    print()

    stop_event = threading.Event()
    tested = [0]
    tested_lock = threading.Lock()
    start_time = time.time()

    # Handle Ctrl+C
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

        token = secrets.token_urlsafe(32)
        headers = {
            "X-Admin-Token": token,
            "User-Agent": "strong-token-demo/1.0 (defensive-test)",
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
                if infinite:
                    print(f"  ... {count:,} tested, {rate:.0f}/s, no hit")
                else:
                    remaining = (attempts - count) / rate if rate > 0 else 0
                    print(f"  ... {count:,}/{attempts:,} tested, "
                          f"{rate:.0f}/s, ~{remaining:.0f}s left, no hit")

            if resp.status_code == 200:
                stop_event.set()
                print(f"\n[{count:,}] token='{token}' -> HTTP 200")
                print(f"\nIMPOSSIBLE: found token '{token}'!")
                print("This should not happen with a strong token.")
                return True
            elif resp.status_code == 403:
                stop_event.set()
                print(f"\n[{count:,}] token='{token}' -> HTTP 403")
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
        if infinite:
            # Submit in batches to avoid memory issues
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

    print(f"\n{'=' * 60}")
    print(f"RESULTS")
    print(f"{'=' * 60}")
    print(f"Tokens tested:    {count:,}")
    print(f"Time elapsed:     {elapsed:.1f}s")
    print(f"Speed:            {rate:.0f} attempts/s")
    print(f"Threads:          {threads}")
    print()

    total_space = 2 ** 256
    fraction_tested = count / total_space if total_space > 0 else 0
    years_needed = (total_space / rate) / (365.25 * 24 * 3600) if rate > 0 else float('inf')

    print(f"Token entropy:    256 bits (secrets.token_urlsafe(32))")
    print(f"Total space:      ~{total_space:.2e} possible tokens")
    print(f"Fraction tested:  {fraction_tested:.2e} of total")
    print(f"At {rate:.0f}/s with {threads} threads:")
    print(f"  Time to cover:  ~{years_needed:.2e} years")
    print(f"  Universe age:   1.38e10 years")
    print()
    print("CONCLUSION: A strong token is practically impossible to brute-force.")
    print(f"Even at {threads} parallel threads, the search space is too vast.")
    print("Defense: use secrets.token_urlsafe(32) + IP whitelist + rate limiting.")
    print("=" * 60)

    return False


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Live strong token brute-force demo. "
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
        help="Number of random tokens to try (default: 10000, 0=infinite).",
    )
    parser.add_argument(
        "--threads",
        type=int,
        default=DEFAULT_THREADS,
        help=f"Number of parallel threads (default: {DEFAULT_THREADS}).",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=DEFAULT_TIMEOUT,
        help=f"Request timeout in seconds (default: {DEFAULT_TIMEOUT}).",
    )
    args = parser.parse_args()

    live_bruteforce(args.url, args.attempts, args.threads, args.timeout)


if __name__ == "__main__":
    main()
