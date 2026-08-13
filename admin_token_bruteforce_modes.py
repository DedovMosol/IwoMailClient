#!/usr/bin/env python3
"""
Admin token brute-force demo with two modes.

Usage:
    python admin_token_bruteforce_modes.py --mode simple
    python admin_token_bruteforce_modes.py --mode large

Modes:
    simple  - Small dictionary of weak passwords. Demonstrates how a weak
              admin token is guessed in seconds. Relevant for Alex if his token
              is simple.
    large   - Millions of random attempts against a secrets.token_urlsafe(32)
              token. Demonstrates that a strong token is impossible to guess.
"""

import argparse
import secrets
import time
import math


def attack_weak_token() -> None:
    """Scenario 1: weak token from a small dictionary."""
    print("=" * 60)
    print("MODE: simple — weak token attack")
    print("=" * 60)
    print("If the admin token is a common word like 'admin123', this is how")
    print("fast an attacker can find it.\n")

    dictionary = [
        "admin", "admin123", "password", "123456", "secret",
        "token", "cap_admin", "anthropic", "claude", "claude_admin",
        "vibecode", "proxy", "root", "qwerty", "letmein",
    ]
    real_token = "admin123"  # example of a weak token

    print(f"Dictionary size: {len(dictionary)}")
    print(f"Example token:     {real_token}")
    print("Starting...\n")

    start = time.time()
    for i, guess in enumerate(dictionary, start=1):
        if guess == real_token:
            elapsed = time.time() - start
            print(f"FOUND: '{guess}' after {i} attempts ({elapsed:.4f}s)")
            speed = i / elapsed if elapsed > 0 else float('inf')
            print(f"Speed: {speed:.0f} attempts/s")
            print("\nConclusion: weak tokens are broken in seconds.\n")
            return

    print("Not found in this small dictionary.")
    print("In real life the dictionary would be much larger.\n")


def attack_strong_token(attempts: int = 1_000_000) -> None:
    """Scenario 2: strong token from secrets.token_urlsafe(32)."""
    print("=" * 60)
    print("MODE: large — strong token attack")
    print("=" * 60)
    print("If the admin token is generated with secrets.token_urlsafe(32),")
    print("this is why brute force is impossible.\n")

    real_token = secrets.token_urlsafe(32)
    token_length = len(real_token)
    # secrets.token_urlsafe(32) generates 32 random bytes = 256 bits of entropy.
    # The base64url encoding produces ~43 chars, but the actual entropy is 256 bits.
    bits_of_entropy = 256
    total_combinations = 2 ** bits_of_entropy

    print(f"Real token:        {real_token}")
    print(f"Token length:      {token_length} chars (base64url)")
    print(f"Random bytes:      32")
    print(f"Entropy:           {bits_of_entropy} bits")
    print(f"Total combinations: ~{total_combinations:.2e}")
    print()

    print(f"Trying {attempts:,} random guesses...")
    start = time.time()
    found = False

    for i in range(1, attempts + 1):
        guess = secrets.token_urlsafe(32)
        if guess == real_token:
            found = True
            print(f"FOUND after {i:,} attempts! (mathematically impossible)")
            break

    elapsed = time.time() - start
    speed = attempts / elapsed if elapsed > 0 else 0

    print(f"Done. Found: {found}")
    print(f"Attempts: {attempts:,}")
    print(f"Time:     {elapsed:.2f}s")
    print(f"Speed:    {speed:,.0f} attempts/s")
    print()

    if not found:
        print("Conclusion: even millions of attempts do not find a strong token.")
        print("At 1 billion attempts per second, the expected time is ~10^61 years.")
        print("The universe is only 13.8 billion (1.38x10^10) years old.\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="Admin token brute-force demo.")
    parser.add_argument(
        "--mode",
        choices=["simple", "large"],
        default="simple",
        help="simple = weak token demo; large = strong token demo",
    )
    parser.add_argument(
        "--attempts",
        type=int,
        default=1_000_000,
        help="Number of attempts for the large mode (default: 1,000,000).",
    )
    args = parser.parse_args()

    if args.mode == "simple":
        attack_weak_token()
    else:
        attack_strong_token(args.attempts)


if __name__ == "__main__":
    main()
