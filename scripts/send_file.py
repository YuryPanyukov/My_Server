#!/usr/bin/env python3
"""
Wi-Fi File Receiver Protocol Client (MVP проверки)

Протокол (бинарный, big-endian):
  [2B: длина имени файла (unsigned short)]
  [N байт: имя файла в UTF-8]
  [8B: размер файла (long)]
  [M байт: содержимое файла]

После отправки клиент читает 1-байтный ответ:
  0x01 - успех
  0x00 - ошибка
"""

import os
import socket
import sys

PORT = 8080  # по умолчанию; можно переопределить третьим аргументом


def send_file(host: str, filepath: str, port: int = PORT) -> None:
    if not os.path.isfile(filepath):
        print("Файл не найден:", filepath, file=sys.stderr)
        sys.exit(1)

    filename_bytes = os.path.basename(filepath).encode("utf-8")
    filesize = os.path.getsize(filepath)

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
            s.connect((host, port))
        except OSError as e:
            print("Не удалось подключиться к", host, ":", port, "-", e, file=sys.stderr)
            sys.exit(1)

        # Заголовок: длина имени (2B), имя (UTF-8), размер (8B, long big-endian)
        s.sendall(len(filename_bytes).to_bytes(2, "big"))
        s.sendall(filename_bytes)
        s.sendall(filesize.to_bytes(8, "big"))

        # Тело файла
        with open(filepath, "rb") as f:
            while True:
                chunk = f.read(65536)
                if not chunk:
                    break
                s.sendall(chunk)

        # Читаем ответ от сервера (1 байт = boolean в java DataOutputStream)
        response = s.recv(1)
        if not response:
            print("Сервер закрыл соединение без ответа", file=sys.stderr)
            sys.exit(1)

        success = response == b"\x01"
        print("Ответ:", "Успех" if success else "Ошибка")


def main() -> None:
    if len(sys.argv) not in (3, 4):
        print(f"Использование: python {sys.argv[0]} <IP_телефона> <путь_к_файлу> [порт]",
              file=sys.stderr)
        print(f"Пример: python {sys.argv[0]} 192.168.1.15 ./test.txt",
              file=sys.stderr)
        sys.exit(1)

    host = sys.argv[1]
    filepath = sys.argv[2]
    port = int(sys.argv[3]) if len(sys.argv) == 4 else PORT
    send_file(host, filepath, port)


if __name__ == "__main__":
    main()
