# SPDX-License-Identifier: Apache-2.0
# Nemuri - Telegram uploader for CI builds (MTProto via Telethon).
# Supports forum topic ids and a cached login session. Author: Anatdx
#
# Env:
#   BOT_TOKEN          (required) bot token from @BotFather
#   CHAT_ID            (required) target chat id (channel/group/user)
#   MESSAGE_THREAD_ID  (optional) forum topic id
#   SESSION_STRING     (optional) cached Telethon StringSession to avoid re-login
#   TITLE, VERSION, BRANCH, COMMIT_MESSAGE, COMMIT_URL, RUN_URL  (caption fields)
#   PRINT_SESSION=1    (optional) print the StringSession then exit (one-time setup helper)
#
# Usage: python3 nemuribot.py <file1.apk> [file2 ...]

import asyncio
import os
import sys

from telethon import TelegramClient
from telethon.sessions import StringSession

# Public Telethon sample api credentials (same ones YukiSU CI uses).
API_ID = 611335
API_HASH = "d524b414d21f4d37f08684c1df41ac9c"

BOT_TOKEN = os.environ.get("BOT_TOKEN")
CHAT_ID = os.environ.get("CHAT_ID")
MESSAGE_THREAD_ID = os.environ.get("MESSAGE_THREAD_ID")
SESSION_STRING = os.environ.get("SESSION_STRING")
COMMIT_URL = os.environ.get("COMMIT_URL", "")
COMMIT_MESSAGE = os.environ.get("COMMIT_MESSAGE", "")
RUN_URL = os.environ.get("RUN_URL", "")
TITLE = os.environ.get("TITLE", "Nemuri")
VERSION = os.environ.get("VERSION", "")
BRANCH = os.environ.get("BRANCH", "")

MSG_TEMPLATE = """
**{title}** `{version}`
Branch: {branch}
#ci_{version}
```
{commit_message}
```
[Commit]({commit_url})
[Workflow run]({run_url})
""".strip()


def get_caption():
    msg = MSG_TEMPLATE.format(
        title=TITLE,
        branch=BRANCH,
        version=VERSION,
        commit_message=COMMIT_MESSAGE,
        commit_url=COMMIT_URL,
        run_url=RUN_URL,
    )
    # Telegram caption hard limit is 1024 chars; fall back to a short link.
    if len(msg) > 1024:
        return COMMIT_URL or f"{TITLE} {VERSION}"
    return msg


def normalize_env():
    global CHAT_ID, MESSAGE_THREAD_ID
    if not BOT_TOKEN:
        print("[-] Missing BOT_TOKEN")
        sys.exit(1)
    if not CHAT_ID:
        print("[-] Missing CHAT_ID")
        sys.exit(1)
    try:
        CHAT_ID = int(CHAT_ID)
    except (TypeError, ValueError):
        pass  # may be a @username
    if MESSAGE_THREAD_ID:
        try:
            MESSAGE_THREAD_ID = int(MESSAGE_THREAD_ID)
        except ValueError:
            print("[-] Invalid MESSAGE_THREAD_ID")
            sys.exit(1)
    else:
        MESSAGE_THREAD_ID = None


async def main():
    print_session = os.environ.get("PRINT_SESSION") == "1"
    if not BOT_TOKEN:
        print("[-] Missing BOT_TOKEN")
        sys.exit(1)
    if not print_session:
        normalize_env()  # PRINT_SESSION mode only needs the bot token
    files = sys.argv[1:]
    print("[+] Logging in to Telegram")
    async with await TelegramClient(
        StringSession(SESSION_STRING), API_ID, API_HASH
    ).start(bot_token=BOT_TOKEN) as bot:
        if print_session:
            # One-time helper: capture this and store it as the SESSION_STRING secret.
            print(f"[+] SESSION_STRING={bot.session.save()}")
            return
        if not files:
            print("[-] No files to upload")
            sys.exit(1)
        print("[+] Files:", files)
        caption = [""] * len(files)
        caption[-1] = get_caption()
        await bot.send_file(
            entity=CHAT_ID,
            file=files,
            caption=caption,
            reply_to=MESSAGE_THREAD_ID,
            parse_mode="markdown",
        )
        print("[+] Done")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except Exception as exc:
        print(f"[-] Upload failed: {exc}")
        sys.exit(1)
