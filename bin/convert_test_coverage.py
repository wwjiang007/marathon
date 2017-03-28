#!/usr/bin/python

# Covert the coverage data into a format that Habormaster understands

import xml.etree.ElementTree as XmlTree
import sys
import json

tree = XmlTree.parse(sys.argv[1])
root = tree.getroot()

# N => Not executable, C = Covered, U = Not Covered
all_coverage = {}
for file in root.findall("./packages/package/classes/class"):
    filename = file.get("filename")
    coverage = {}

    for line in file.findall("./methods/method/lines/line"):
        currentCoverage = coverage.get(line.get("number"), 0)
        coverage[line.get("number")] = currentCoverage + int(line.get("hits"))


    phab_coverage = ""
    keys = sorted(coverage.keys())
    end_line = keys[-1]
    for i in range(1, int(end_line)):
        hits = coverage.get(str(i), -1)
        if hits < 0:
            phab_coverage += "N"
        elif hits == 0:
            phab_coverage += "U"
        else:
            phab_coverage += "C"

    all_coverage[filename] = phab_coverage

phab_data = {"unit": [
    {"name": "{} Coverage".format(sys.argv[2]),
     "result": "pass",
     "coverage": all_coverage
    }
] }

print json.dumps(phab_data)
