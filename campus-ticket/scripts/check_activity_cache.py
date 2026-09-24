"""Concurrent read-only activity requests; check server SQL logs for cache hits."""
import argparse
import collections
import concurrent.futures
import json
import threading
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--activity-id", type=int, required=True)
parser.add_argument("--workers", type=int, default=12, choices=range(1, 33))
parser.add_argument("--wait-seconds", type=int, default=31, choices=range(0, 61))
args = parser.parse_args()
url = f"http://localhost:8081/activities/{args.activity_id}"
barrier = threading.Barrier(args.workers)


def request_one(index):
    barrier.wait(timeout=15)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(url, timeout=15) as response:
            body = json.load(response)
            result = {"status": response.status, "activity_id": body.get("id")}
    except urllib.error.HTTPError as error:
        result = {"status": error.code}
    except Exception as error:
        result = {"status": "ERROR", "error": str(error)}
    result["elapsed_ms"] = round((time.perf_counter() - started) * 1000, 1)
    return result


print(f"Waiting {args.wait_seconds}s for cache expiry; avoid other requests.", flush=True)
time.sleep(args.wait_seconds)
print("Concurrent requests starting at", time.strftime("%H:%M:%S"), flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
    results = list(pool.map(request_one, range(args.workers)))
print(json.dumps({"status_counts": dict(collections.Counter(str(r["status"]) for r in results)),
                  "results": results}, indent=2))
print("HTTP success does not prove a single SQL query. Inspect ActivityMapper.findById logs.")
raise SystemExit(0 if all(r.get("status") == 200 and r.get("activity_id") == args.activity_id
                         for r in results) else 1)
