#!/usr/bin/env python3
"""
Infinite token brute-force demo (educational / defensive)

Generates a strong admin token, then tries to guess it forever by generating
random tokens. The script never finds the token in practice; press Ctrl+C to
stop and see the statistics.

Use this to demonstrate why a properly generated token is safe from brute force.
"""

import secrets
import time
import math
import signal
import sys


running = True
attempts = 0
start_time = 0.0


def signal_handler(signum, frame):
    global running
    running = False


def generate_token() -> str:
    return secrets.token_urlsafe(32)


def format_number(n: float) -> str:
    if n >= 1e12:
        return f"{n:.2e}"
    return f"{n:,.0f}"


def main() -> None:
    global attempts, start_time, running

    signal.signal(signal.SIGINT, signal_handler)

    print("=" * 60)
    print("INFINITE BRUTE-FORCE DEMO")
    print("=" * 60)
    print("This will generate random tokens forever and compare them to a")
    print("real token. It will NOT find the token in practice.")
    print("Press Ctrl+C to stop and see statistics.\n")

    real_token = generate_token()
    print(f"Real token:      {real_token}")
    print(f"Token length:    {len(real_token)} chars")
    print(f"Combinations:    ~{64 ** len(real_token):.2e} (2^{math.log2(64 ** len(real_token)):.0f})")
    print()

    start_time = time.time()
    last_print_time = start_time
    print_every = 100_000

    print("Starting...\n")

    while running:
        guess = generate_token()
        attempts += 1

        if guess == real_token:
            elapsed = time.time() - start_time
            print(f"\nFOUND after {format_number(attempts)} attempts! (impossible in reality)")
            print(f"Time: {elapsed:.2f}s")
            return

        if attempts % print_every == 0:
            now = time.time()
            elapsed = now - start_time
            speed = attempts / elapsed
            print(f"Attempts: {format_number(attempts)} | Time: {elapsed:.1f}s | "
                  f"Speed: {format_number(speed)}/s | Not found")
            last_print_time = now

    # Ctrl+C pressed
    elapsed = time.time() - start_time
    speed = attempts / elapsed if elapsed > 0 else 0

    print("\n" + "=" * 60)
    print("STOPPED BY USER")
    print("=" * 60)
    print(f"Total attempts:  {format_number(attempts)}")
    print(f"Time elapsed:    {elapsed:.2f}s")
    print(f"Average speed:   {format_number(speed)} attempts/s")
    print(f"Token not found: as expected for a strong random token.")
    print()
    print("Even with unlimited time, the chance of guessing")
    print(f"a {len(real_token)}-char token_urlsafe token is practically zero.")


if __name__ == "__main__":
    main()
