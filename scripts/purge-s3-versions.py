import json
import subprocess
import sys

bucket = sys.argv[1]
while True:
    raw = subprocess.check_output(
        ["aws", "s3api", "list-object-versions", "--bucket", bucket, "--output", "json"]
    )
    data = json.loads(raw or b"{}")
    objects = []
    for kind in ("Versions", "DeleteMarkers"):
        for item in data.get(kind) or []:
            objects.append({"Key": item["Key"], "VersionId": item["VersionId"]})
    if not objects:
        break
    for start in range(0, len(objects), 1000):
        chunk = json.dumps({"Objects": objects[start : start + 1000], "Quiet": True})
        subprocess.check_call(
            ["aws", "s3api", "delete-objects", "--bucket", bucket, "--delete", chunk]
        )
