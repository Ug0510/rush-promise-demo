#!/usr/bin/env python3
"""Check Rush Promise's Development rule contract and emit only approved fixture/evidence fields."""

import argparse
import hashlib
import json
import os
import sys
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from urllib.error import URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


OFFICIAL = "https://api.flagsmith.com/api/v1/environment-document/"
POLICY_FLAG = "promise_profile"
RUSH_FLAG = "rush_mode"
EXPECTED = {
    "NORMAL": ("STANDARD", 4000, 15, 180, 30, 15),
    "BUSY_SLOT": ("SLOT_NEAR_CAPACITY", 2000, 30, 120, 20, 10),
    "RUSH": ("RUSH_HOUR", 3000, 30, 120, 20, 15),
}
BOUNDS = {"approval_above_g": (0, 5000), "lead_time_min": (0, 120), "quote_ttl_s": (60, 600),
          "grace_min": (5, 60), "hold_min": (5, 60)}
OPERATION_SWITCHES = ("voice_intent_chat", "voice_dynamic_tts")


class Reject(ValueError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def require(test, failure):
    if not test:
        raise Reject(failure)


def compact(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def render_revision(raw):
    revision = datetime.fromisoformat(raw)
    require(revision.tzinfo is not None, "updated_at must include a timezone")
    revision = revision.astimezone(timezone.utc)
    precision = "seconds" if revision.microsecond == 0 else "milliseconds" if revision.microsecond % 1000 == 0 else "microseconds"
    return revision, revision.isoformat(timespec=precision).replace("+00:00", "Z")


def named(states, name):
    required = [row for row in states if row.get("feature", {}).get("name") == name]
    require(len(required) == 1, f"expected exactly one {name} state")
    value = required[0]
    require(value.get("multivariate_feature_state_values") == [], f"{name} must not be multivariate")
    return value


def profile(state, name):
    require(state.get("enabled") is True, f"{name} must be enabled")
    try:
        value = json.loads(state.get("feature_state_value"))
    except (ValueError, TypeError):
        raise Reject(f"{name} must have a JSON value") from None
    require(isinstance(value, dict), f"{name} profile must be a JSON object")
    for field in ("schema", "profile", "reason_code", *BOUNDS):
        require(field in value, f"{name}.{field} is missing")
    require(len(value) == 8, f"{name} unexpected profile fields")
    require(type(value["schema"]) is int and value["schema"] == 1, f"{name}.schema must be 1")
    require(value["profile"] == name and value["reason_code"] == EXPECTED[name][0], f"{name}.profile/reason_code mismatch")
    for field, (lower, upper) in BOUNDS.items():
        require(type(value[field]) is int and lower <= value[field] <= upper, f"{name}.{field} is out of range or not an integer")
    exact = (value["reason_code"], *(value[field] for field in BOUNDS))
    require(exact == EXPECTED[name], f"{name} does not match the staged profile values")
    return {field: value[field] for field in ("schema", "profile", "reason_code", *BOUNDS)}


def override(segments, name, property_name, operator, operand, profile_name):
    found = [s for s in segments if s.get("name") == name]
    require(len(found) == 1, f"required segment {name} missing or duplicated")
    top = found[0].get("rules")
    require(isinstance(top, list) and len(top) == 1, f"{name}.rules")
    rule = top[0]
    for _ in range(6):
        require(rule.get("type") in {"ALL", "ANY"}, f"{name}.rule type")
        if rule.get("conditions"):
            break
        nested = rule.get("rules")
        require(isinstance(nested, list) and len(nested) == 1, f"{name}.rule shape")
        rule = nested[0]
    conditions = rule.get("conditions", [])
    require(len(conditions) == 1 and rule.get("rules") == [], f"{name} must have exactly one condition")
    condition = conditions[0]
    actual = condition.get("value")
    if type(actual) is bool:
        actual = str(actual).lower()
    require((condition.get("property_"), condition.get("operator"), str(actual)) == (property_name, operator, operand),
            f"{name} expected {property_name} {operator} {operand}")
    state = named(found[0]["feature_states"], POLICY_FLAG)
    priority = state.get("feature_segment", {}).get("priority")
    require(type(priority) is int and 0 <= priority <= 2_147_483_647, f"{name}.priority")
    return (priority, profile(state, profile_name), {"property_": property_name, "operator": operator, "value": operand})


def project_state(identifier, name, enabled, value=None, priority=None):
    return {"django_id": identifier, "enabled": enabled,
            "feature": {"id": 1 if name == RUSH_FLAG else 2, "name": name, "type": "STANDARD"},
            "multivariate_feature_state_values": [], "feature_segment": None if priority is None else {"priority": priority},
            "feature_state_value": None if value is None else compact(value)}


def check(doc, header, expect_rush):
    _, version = render_revision(doc["updated_at"])
    if header is not None:
        epoch = datetime(1970, 1, 1, tzinfo=timezone.utc)
        timestamp, _ = render_revision(doc["updated_at"])
        offset = timestamp - epoch
        require(Decimal(header) == Decimal(offset.days * 86400 + offset.seconds) + Decimal(offset.microseconds) / 1_000_000,
                "provider revision header disagrees with document")
    require(doc.get("identity_overrides") == [], "identity overrides are outside this policy")
    states = doc["feature_states"]
    segments = doc["project"]["segments"]
    rush = named(states, RUSH_FLAG)
    require(type(rush.get("enabled")) is bool and rush.get("feature_segment") is None, "rush_mode must be an environment boolean")
    if expect_rush is not None:
        require(rush["enabled"] is expect_rush, f"rush_mode is still {'ON' if rush['enabled'] else 'OFF'}")
    normal = profile(named(states, POLICY_FLAG), "NORMAL")
    actual = lambda row: row.get("feature", {}).get("name")
    segment_states = [row for segment in segments for row in segment.get("feature_states", [])]
    require(not any(actual(row) == RUSH_FLAG for row in segment_states), "unexpected rush_mode override")
    for name in OPERATION_SWITCHES:
        existing = [state for state in states if actual(state) == name]
        if existing:
            value = named(states, name)
            require(type(value.get("enabled")) is bool and value.get("feature_segment") is None
                    and not any(actual(row) == name for row in segment_states), f"{name} is not a global boolean")
    require(sum(actual(row) == POLICY_FLAG for row in segment_states) == 2, "promise_profile requires exactly two overrides")
    busy = override(segments, "busy-slot", "slot_load_pct", "GREATER_THAN_INCLUSIVE", "80", "BUSY_SLOT")
    rush_hour = override(segments, "rush-hour", "rush", "EQUAL", "true", "RUSH")
    require(busy[0] < rush_hour[0], "busy-slot must precede rush-hour")
    projected = {"updated_at": version, "api_key": "local-evaluation-only", "name": "Development", "identity_overrides": [],
                 "feature_states": [project_state(1, RUSH_FLAG, rush["enabled"]), project_state(2, POLICY_FLAG, True, normal)],
                 "project": {"segments": []}}
    for index, (name, (priority, value, condition)) in enumerate((("busy-slot", busy), ("rush-hour", rush_hour)), 1):
        projected["project"]["segments"].append({"id": index, "name": name,
                "rules": [{"type": "ALL", "rules": [], "conditions": [condition]}],
                "feature_states": [project_state(index + 2, POLICY_FLAG, True, value, priority)]})
    evidence = {"source": "HOSTED_DEVELOPMENT" if header is not None else "OFFLINE_FIXTURE", "revision": version,
                "digest": hashlib.sha256(compact(projected).encode()).hexdigest(), "rushMode": rush["enabled"],
                "default": normal, "overrides": [{"name": name, "priority": row[0], "condition": row[2], "profile": row[1]}
                    for name, row in (("busy-slot", busy), ("rush-hour", rush_hour))]}
    return projected, evidence


def fetch(url):
    parsed = urlsplit(url)
    require(parsed.hostname in {"api.flagsmith.com", "edge.api.flagsmith.com"} and parsed.scheme == "https"
            and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment,
            "hosted smoke requires the official HTTPS Flagsmith domain")
    key = os.environ.get("FLAGSMITH_SERVER_KEY", "").strip()
    source = os.environ.get("FLAGSMITH_SERVER_KEY_FILE")
    if source and not key:
        key = Path(source).read_text().strip()
    if not key:
        raise OSError("missing server environment key")
    req = Request(f"https://{parsed.hostname}/api/v1/environment-document/", headers={"X-Environment-Key": key,
                  "Accept": "application/json", "Cache-Control": "no-cache"})
    with build_opener(NoRedirect).open(req, timeout=9) as response:
        document = response.read(512 * 1024 + 1)
        if len(document) > 512 * 1024 or response.headers.get("X-Flagsmith-Document-Updated-At") is None:
            raise OSError("missing revision or oversized response")
        return json.loads(document), response.headers["X-Flagsmith-Document-Updated-At"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", type=Path, help="read a saved allowlisted fixture")
    parser.add_argument("--api-url", default=os.environ.get("FLAGSMITH_API_URL", OFFICIAL))
    parser.add_argument("--expect-rush", choices=("on", "off"))
    parser.add_argument("--save-fixture", "--save", type=Path, help="write only a projected fixture with synthetic identifiers")
    parser.add_argument("--evidence", type=Path, help="write the redacted rule check results")
    args = parser.parse_args()
    try:
        if args.file:
            doc, header = json.loads(args.file.read_text()), None
        else:
            doc, header = fetch(args.api_url)
    except (OSError, ValueError, URLError) as failure:
        print(f"Flagsmith source unavailable ({type(failure).__name__})", file=sys.stderr)
        return 2
    try:
        projected, evidence = check(doc, header, None if args.expect_rush is None else args.expect_rush == "on")
    except Reject as failure:
        print(f"Rule check failed: {failure}", file=sys.stderr)
        return 1
    except (ValueError, TypeError, KeyError, AttributeError):
        print("Rule check failed: invalid environment document fields", file=sys.stderr)
        return 1
    for path, data in ((args.save_fixture, projected), (args.evidence, evidence)):
        if path is not None:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
