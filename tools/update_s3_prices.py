#!/usr/bin/env python3
"""Regenerates app/src/main/kotlin/com/s3vault/app/domain/cost/S3PriceTable.kt from AWS's official, public Price List
Bulk API (no credentials, no account, nothing about your bucket is sent):

    https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonS3/current/region_index.json

Usage:
    python tools/update_s3_prices.py                 # downloads the per-region files to a temp dir, then generates
    python tools/update_s3_prices.py --from-dir DIR  # reuse region files already downloaded into DIR (<region>.json)

The app never calls a pricing service: it ships this table, shows the publication date of the price list it came from
and warns when the table gets old. Re-run this script (and commit the result) to refresh the prices.

What is extracted (USD, On-Demand, per region): S3 Standard storage tiers, Standard-IA, One Zone-IA, Glacier Instant
Retrieval, Glacier Flexible Retrieval ("Amazon Glacier") and Intelligent-Tiering Frequent Access storage, the
Intelligent-Tiering monitoring fee, PUT/COPY/POST/LIST (Tier1) and GET (Tier2) request prices of S3 Standard and the
per-GB retrieval fees of Standard-IA, One Zone-IA and Glacier Instant Retrieval.
Not extracted on purpose: Glacier Deep Archive (the price list carries no plain storage product for it, only staging),
data transfer, Select, Inventory, Storage Lens and the other features: the app does not estimate them.
"""
import argparse
import json
import os
import re
import sys
import tempfile
import urllib.request
from datetime import date

BASE = "https://pricing.us-east-1.amazonaws.com"
INDEX = BASE + "/offers/v1.0/aws/AmazonS3/current/region_index.json"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "kotlin", "com", "s3vault", "app", "domain", "cost", "S3PriceTable.kt")
VOLUME_TYPES = {
    "Standard": "standard", "Standard - Infrequent Access": "standardIa", "One Zone - Infrequent Access": "oneZoneIa",
    "Glacier Instant Retrieval": "glacierIr", "Amazon Glacier": "glacier", "Intelligent-Tiering Frequent Access": "intFrequent",
}
SKIP = re.compile(r"^(aws-other)$|-(han|ath|ist)-\d$")  # not regions an S3 bucket lives in (local zones, "other")


def fetch(url, dest):
    with urllib.request.urlopen(url, timeout=120) as r, open(dest, "wb") as f:
        f.write(r.read())


def usage_matches(usagetype, base):
    # Region-specific usage types carry a region prefix (EUW1-TimedStorage-ByteHrs); us-east-1 has none. The prefix is
    # matched strictly: other products reuse the same suffix (Files-TimedStorage-ByteHrs, Tables-..., Annotation-...).
    return re.fullmatch(r"(?:[A-Z]{2,4}[0-9]?-)?" + re.escape(base), usagetype) is not None


def dims(terms, sku):
    out = []
    for t in terms.get(sku, {}).values():
        for pd in t["priceDimensions"].values():
            end = pd["endRange"]
            out.append((float(pd["beginRange"]), None if end == "Inf" else float(end), float(pd["pricePerUnit"]["USD"]), pd["unit"]))
    return sorted(out, key=lambda x: x[0])


def put(found, key, value, path):
    if key in found:
        raise SystemExit("ambiguous price list: two products match %s in %s" % (key, path))
    found[key] = value


def extract(path):
    d = json.load(open(path, encoding="utf-8"))
    prods, terms = d["products"], d["terms"]["OnDemand"]
    found = {}
    for sku, p in prods.items():
        fam, a = p.get("productFamily"), p["attributes"]
        ut = a.get("usagetype", "")
        if fam == "Storage":
            # volumeType is exact; usagetype is shared with other products (e.g. Intelligent-Tiering object overhead).
            key = VOLUME_TYPES.get(a.get("volumeType"))
            if key:
                put(found, key, dims(terms, sku), path)
        else:
            # Outside us-east-1 several of these products have no productFamily, so match on the (strict) usage type only.
            for key, base in (("putPerRequest", "Requests-Tier1"), ("getPerRequest", "Requests-Tier2"),
                              ("retrievalStandardIaPerGb", "Retrieval-SIA"), ("retrievalOneZoneIaPerGb", "Retrieval-ZIA"), ("retrievalGlacierIrPerGb", "Retrieval-GIR"), ("intMonitorPerObject", "Monitoring-Automation-INT")):
                if usage_matches(ut, base):
                    put(found, key, dims(terms, sku), path)
    return d["publicationDate"], d["version"], found


def flat(rows):
    return rows[0][2] if rows and len(rows) == 1 else None


def tiers_kt(rows):
    # (upper bound of the tier in GB or null for the last one, USD per GB-month)
    return "listOf(" + ", ".join("Tier(%s, %s)" % ("null" if end is None else repr(int(end)), repr(price)) for _, end, price, _ in rows) + ")"


def num(x):
    return "null" if x is None else repr(x)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from-dir", help="directory with <region>.json files already downloaded")
    args = ap.parse_args()
    workdir = args.from_dir
    index = None
    if workdir is None:
        workdir = tempfile.mkdtemp(prefix="s3prices-")
        fetch(INDEX, os.path.join(workdir, "region_index.json"))
        index = json.load(open(os.path.join(workdir, "region_index.json")))
        for code, v in sorted(index["regions"].items()):
            if SKIP.search(code):
                continue
            fetch(BASE + v["currentVersionUrl"], os.path.join(workdir, code + ".json"))
    else:
        index = json.load(open(os.path.join(workdir, "region_index.json")))

    regions, pub, versions = {}, set(), set()
    for code in sorted(index["regions"]):
        path = os.path.join(workdir, code + ".json")
        if SKIP.search(code) or not os.path.exists(path):
            continue
        p, v, f = extract(path)
        pub.add(p); versions.add(v)
        if "standard" not in f or "putPerRequest" not in f or "getPerRequest" not in f:
            print("WARNING: %s lacks Standard storage or request prices, skipped" % code, file=sys.stderr)
            continue
        regions[code] = f
    if len(pub) != 1:
        print("WARNING: several publication dates: %s" % sorted(pub), file=sys.stderr)
    publication = sorted(pub)[-1]

    lines = []
    w = lines.append
    w("// GENERATED by tools/update_s3_prices.py - do not edit by hand.")
    w("// Source: AWS Price List Bulk API (official, public), offer AmazonS3, version %s." % ", ".join(sorted(versions)))
    w("// Prices are On-Demand list prices in USD; they ignore discounts, credits, taxes and the free tier.")
    w("package com.s3vault.app.domain.cost")
    w("")
    w("/** Publication date of the AWS price list these prices come from. */")
    w('const val S3_PRICE_LIST_PUBLISHED = "%s"' % publication[:10])
    w("")
    w("/** Day the table was generated from that list. */")
    w('const val S3_PRICE_TABLE_GENERATED = "%s"' % date.today().isoformat())
    w("")
    w("internal val S3_PRICES: Map<String, S3RegionPrices> = mapOf(")
    for code, f in regions.items():
        w('    "%s" to S3RegionPrices(' % code)
        w("        standard = %s," % tiers_kt(f["standard"]))
        w("        standardIa = %s, oneZoneIa = %s, glacierIr = %s, glacierFlexible = %s," % tuple(num(flat(f.get(k))) for k in ("standardIa", "oneZoneIa", "glacierIr", "glacier")))
        w("        intelligentFrequent = %s," % (tiers_kt(f["intFrequent"]) if "intFrequent" in f else "emptyList()"))
        w("        intelligentMonitoringPerObject = %s," % num(flat(f.get("intMonitorPerObject"))))
        w("        putPerRequest = %s, getPerRequest = %s," % (num(flat(f["putPerRequest"])), num(flat(f["getPerRequest"]))))
        w("        retrievalStandardIaPerGb = %s, retrievalOneZoneIaPerGb = %s, retrievalGlacierIrPerGb = %s," % tuple(num(flat(f.get(k))) for k in ("retrievalStandardIaPerGb", "retrievalOneZoneIaPerGb", "retrievalGlacierIrPerGb")))
        w("    ),")
    w(")")
    out = os.path.normpath(OUT)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    print("wrote %s: %d regions, price list published %s" % (out, len(regions), publication))


if __name__ == "__main__":
    main()
