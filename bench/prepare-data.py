#!/usr/bin/env python3
"""生成压测用户 SQL 与 Redis Token RESP 文件（仅使用 Python 标准库）。"""
import argparse
import hashlib
from pathlib import Path


def resp(*parts: str) -> bytes:
    out = [f"*{len(parts)}\r\n".encode()]
    for part in parts:
        data = part.encode()
        out.append(f"${len(data)}\r\n".encode())
        out.append(data + b"\r\n")
    return b"".join(out)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--users", type=int, default=200_000)
    parser.add_argument("--start-id", type=int, default=1_000_000)
    parser.add_argument("--seed", default="voucherx-01-2026")
    parser.add_argument("--ttl", type=int, default=7200)
    parser.add_argument("--tokens-file", required=True)
    parser.add_argument("--sql-file", required=True)
    parser.add_argument("--resp-file", required=True)
    args = parser.parse_args()

    tokens_path = Path(args.tokens_file)
    sql_path = Path(args.sql_file)
    resp_path = Path(args.resp_file)
    for path in (tokens_path, sql_path, resp_path):
        path.parent.mkdir(parents=True, exist_ok=True)

    tokens = []
    with sql_path.open("w", encoding="utf-8") as sql, resp_path.open("wb") as redis:
        sql.write("SET NAMES utf8mb4;\n")
        batch = []
        for index in range(args.users):
            user_id = args.start_id + index
            token = hashlib.sha256(f"{args.seed}:{user_id}".encode()).hexdigest()[:32]
            tokens.append(token)
            phone = f"15{user_id:09d}"[-11:]
            batch.append(f"({user_id},'{phone}','压测用户{user_id}','')")
            if len(batch) == 2000:
                write_users(sql, batch)
                batch.clear()
            redis.write(resp("SET", f"voucherx:auth:token:{token}", str(user_id),
                             "EX", str(args.ttl)))
        if batch:
            write_users(sql, batch)

    tokens_path.write_text("\n".join(tokens) + "\n", encoding="utf-8")
    print(f"users={args.users} start_id={args.start_id} tokens={len(tokens)}")
    print(f"tokens_file={tokens_path}")
    print(f"sql_file={sql_path} bytes={sql_path.stat().st_size}")
    print(f"resp_file={resp_path} bytes={resp_path.stat().st_size}")


def write_users(sql, batch) -> None:
    sql.write("INSERT IGNORE INTO tb_user (id, phone, nick_name, icon) VALUES\n")
    sql.write(",\n".join(batch))
    sql.write(";\n")


if __name__ == "__main__":
    main()
