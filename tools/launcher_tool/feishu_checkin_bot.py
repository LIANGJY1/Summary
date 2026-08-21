#!/usr/bin/env python3
"""Telegram Bot for remote Feishu check-in.

Usage:
    python3 feishu_checkin_bot.py --token <BOT_TOKEN> --user <TELEGRAM_USER_ID> --pin <PHONE_PIN>
"""

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.parse import urlencode


BASE_URL = "https://api.telegram.org/bot{token}/{method}"


class TelegramBot:
    def __init__(self, token: str, allowed_user_id: int, pin: str, checkin_script: str):
        self.token = token
        self.allowed_user_id = allowed_user_id
        self.pin = pin
        self.checkin_script = checkin_script
        self.offset = 0

    def _api_call(self, method: str, params: dict = None, data: dict = None) -> dict:
        url = BASE_URL.format(token=self.token, method=method)
        if params:
            url += "?" + urlencode(params)

        req = Request(url, method="POST" if data else "GET")
        if data:
            req.add_header("Content-Type", "application/json")
            req.data = json.dumps(data).encode("utf-8")

        try:
            with urlopen(req, timeout=60) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as e:
            return {"ok": False, "description": str(e)}

    def get_updates(self) -> list:
        result = self._api_call("getUpdates", {"offset": self.offset, "limit": 10})
        if not result.get("ok"):
            print(f"getUpdates failed: {result.get('description')}")
            return []

        updates = result.get("result", [])
        if updates:
            self.offset = max(u["update_id"] for u in updates) + 1
        return updates

    def send_message(self, chat_id: int, text: str) -> dict:
        # Telegram message limit is 4096, split if needed
        chunks = [text[i:i+4000] for i in range(0, len(text), 4000)]
        for chunk in chunks:
            self._api_call("sendMessage", data={"chat_id": chat_id, "text": chunk})

    def send_photo(self, chat_id: int, photo_path: str, caption: str = "") -> dict:
        url = BASE_URL.format(token=self.token, method="sendPhoto")
        boundary = "----FormBoundary"

        with open(photo_path, "rb") as f:
            photo_data = f.read()

        body = []
        body.append(f"--{boundary}".encode())
        body.append(b'Content-Disposition: form-data; name="chat_id"')
        body.append(b"")
        body.append(str(chat_id).encode())

        body.append(f"--{boundary}".encode())
        body.append(b'Content-Disposition: form-data; name="caption"')
        body.append(b"")
        body.append(caption.encode("utf-8"))

        body.append(f"--{boundary}".encode())
        body.append(b'Content-Disposition: form-data; name="photo"; filename="result.png"')
        body.append(b"Content-Type: image/png")
        body.append(b"")
        body.append(photo_data)
        body.append(f"--{boundary}--".encode())

        req = Request(url, data=b"\r\n".join(body), method="POST")
        req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")

        try:
            with urlopen(req, timeout=60) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as e:
            return {"ok": False, "description": str(e)}

    def run_checkin(self) -> tuple:
        cmd = ["python3", self.checkin_script, "usb", "5555", self.pin]
        try:
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=120
            )
            output = result.stdout + result.stderr
            return output, result.returncode
        except subprocess.TimeoutExpired:
            return "打卡脚本执行超时", 1
        except Exception as e:
            return f"执行失败: {e}", 1

    def handle_update(self, update: dict):
        message = update.get("message")
        if not message:
            return

        chat_id = message["chat"]["id"]
        user_id = message["from"]["id"]
        text = message.get("text", "")

        if user_id != self.allowed_user_id:
            self.send_message(chat_id, "未授权的用户")
            return

        if text.strip() == "/checkin":
            self.send_message(chat_id, "开始执行打卡...")
            output, code = self.run_checkin()
            self.send_message(chat_id, f"执行结果:\n```\n{output}\n```")

            result_png = Path("/tmp/feishu_checkin_result.png")
            failed_png = Path("/tmp/feishu_checkin_failed.png")

            if code == 0 and result_png.exists():
                self.send_photo(chat_id, str(result_png), "打卡成功")
            elif failed_png.exists():
                self.send_photo(chat_id, str(failed_png), "打卡失败截图")
        elif text.strip() == "/help":
            self.send_message(chat_id, "可用命令:\n/checkin - 执行飞书打卡")
        else:
            self.send_message(chat_id, "未知命令，发送 /help 查看帮助")

    def run(self):
        print("Bot started. Send /checkin from authorized user.")
        me = self._api_call("getMe")
        if me.get("ok"):
            bot_name = me["result"].get("username", "unknown")
            print(f"Bot: @{bot_name}")
        else:
            print(f"Failed to get bot info: {me}")

        while True:
            try:
                updates = self.get_updates()
                for update in updates:
                    self.handle_update(update)
            except Exception as e:
                print(f"Error in main loop: {e}")
            time.sleep(2)


def main():
    parser = argparse.ArgumentParser(description="Telegram Bot for remote Feishu check-in")
    parser.add_argument("--token", required=True, help="Telegram Bot Token")
    parser.add_argument("--user", required=True, type=int, help="Authorized Telegram user ID")
    parser.add_argument("--pin", default="", help="Phone unlock PIN")
    parser.add_argument("--script", default="./feishu_checkin.py", help="Path to check-in script")
    args = parser.parse_args()

    script_path = Path(args.script).resolve()
    if not script_path.exists():
        print(f"Check-in script not found: {script_path}")
        sys.exit(1)

    bot = TelegramBot(args.token, args.user, args.pin, str(script_path))
    bot.run()


if __name__ == "__main__":
    main()
