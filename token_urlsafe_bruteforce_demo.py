#!/usr/bin/env python3
"""
Token generation + brute-force demo (educational / defensive)

Demonstrates how `secrets.token_urlsafe(32)` is generated and why
brute-forcing it is computationally impossible.
"""

import secrets
import time
import math


def generate_admin_token() -> str:
    """Generate a strong admin token the same way a secure backend should."""
    token = secrets.token_urlsafe(32)
    return token


def demo_token_generation() -> None:
    print("=" * 60)
    print("STEP 1: Token generation")
    print("=" * 60)
    for i in range(5):
        token = generate_admin_token()
        print(f"  token {i+1}: {token} (length: {len(token)})")
    print()


def demo_bruteforce_impossible(attempts: int = 100_000) -> None:
    print("=" * 60)
    print("STEP 2: Brute-force demonstration")
    print("=" * 60)

    real_token = generate_admin_token()
    print(f"Real token:    {real_token}")
    print(f"Token length:  {len(real_token)} chars")
    print(f"Alphabet size: 64 characters (A-Z, a-z, 0-9, -, _)")
    print()

    possible_combinations = 64 ** len(real_token)
    print(f"Possible combinations: ~{possible_combinations:.2e}")
    print(f"That is roughly 2^{math.log2(possible_combinations):.0f}")
    print()

    print(f"Trying {attempts:,} random guesses...")
    start = time.time()

    found = False
    for i in range(1, attempts + 1):
        guess = secrets.token_urlsafe(32)
        if guess == real_token:
            found = True
            print(f"FOUND after {i:,} attempts! (extremely unlikely)")
            break

    elapsed = time.time() - start
    print(f"Done. Found: {found}. Time: {elapsed:.2f}s")
    print()

    print("=" * 60)
    print("CONCLUSION")
    print("=" * 60)
    print("A token_urlsafe(32) token cannot be brute-forced in practice.")
    print("An attacker would need to try ~2^192 combinations on average.")
    print("Even with billions of attempts per second, this takes far longer")
    print("than the age of the universe.")
    print()
    print("BUT: if the token is weak (like 'admin123'), it is guessed in seconds.")
    print("Always use secrets.token_urlsafe(32) or longer for admin tokens.")


def demo_comparison_with_weak_token() -> None:
    print("=" * 60)
    print("STEP 3: Comparison with weak token")
    print("=" * 60)

    weak_tokens = [
        "admin", "admin123", "password", "123456", "secret",
        "token", "cap_admin", "anthropic", "claude", "claude_admin",
    ]
    real_weak_token = "admin123"

    print(f"Real weak token: {real_weak_token}")
    print(f"Dictionary size: {len(weak_tokens)}")

    start = time.time()
    for i, guess in enumerate(weak_tokens, start=1):
        if guess == real_weak_token:
            elapsed = time.time() - start
            print(f"FOUND: '{guess}' after {i} attempts ({elapsed:.4f}s)")
            print("This is why admin123 must never be used.")
            return


def main() -> None:
    demo_token_generation()
    demo_bruteforce_impossible(attempts=100_000)
    demo_comparison_with_weak_token()


if __name__ == "__main__":
    main()
