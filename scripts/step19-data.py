"""Create/reset/clean journaled local fixtures. Usage: python scripts/step19-data.py --help"""
import argparse
import json
import uuid

from step19_support import CONFIG, Fixture, load_env, save, snapshot


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("seed", "reset", "cleanup", "inspect"))
    parser.add_argument("--run-id", help="UUID printed by seed; required for other actions")
    parser.add_argument("--drivers", type=int, default=CONFIG["defaultDrivers"])
    parser.add_argument("--orders", type=int, default=CONFIG["defaultOrders"])
    args = parser.parse_args()
    if args.action != "seed" and not args.run_id:
        parser.error("--run-id is required")
    load_env()
    fixture = Fixture(args.run_id or str(uuid.uuid4()))
    with fixture.locked():
        if args.action == "seed":
            if fixture.data is not None:
                parser.error("Run already exists; use reset or a new UUID (existing evidence preserved)")
            print(f"runId={fixture.run_id} directory={fixture.directory}", flush=True)
            save(fixture.directory / "environment.json", snapshot())
            fixture.seed(args.drivers, args.orders)
        elif args.action == "reset":
            fixture.reset()
        elif args.action == "cleanup":
            fixture.cleanup()
        elif not fixture.data:
            parser.error("Run does not exist")
        # Manifest contains private passwords; inspection deliberately shows counts/IDs only.
        print(json.dumps({"runId": fixture.run_id, "status": fixture.data["status"],
                          "accounts": len(fixture.data["accounts"]),
                          "syntheticOrders": fixture.data["syntheticOrders"],
                          "apiRequests": len(fixture.data["apiRequests"]),
                          "directory": str(fixture.directory)}, indent=2))


if __name__ == "__main__":
    main()
