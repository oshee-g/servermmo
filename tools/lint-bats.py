#!/usr/bin/env python3
"""
Static checks for the .bat scripts of this pack (a "linter" for the small
subset of batch that the scripts use). Catches the mistakes that are easy
to make by hand:

  * goto/call to a label that does not exist, or a duplicated label
  * %VARIABLES% that nothing ever defines
  * odd number of double quotes on a line
  * :: comments inside ( ) blocks (they break the block in cmd.exe)
  * unescaped special characters inside echo lines in a block
  * labels immediately followed by "(" (cmd quirk)
  * non-ASCII bytes (codepage noise on Windows)
  * mixed / missing line endings

Run:  python3 tools/lint-bats.py
"""

import glob
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVER = os.path.join(REPO, 'server')

PSEUDO_VARS = {'cd', 'errorlevel', 'date', 'time', 'random', '~dp0', '0', '1', '2',
               'path', 'temp', 'tmp', 'username', 'computername', 'systemroot', 'pwd'}

errors = []
warnings = []


def report(kind, path, lineno, message):
    entry = '%s:%s: %s' % (os.path.basename(path), lineno, message)
    (errors if kind == 'error' else warnings).append(entry)


def strip_comment(line):
    return re.sub(r'\s+rem\b.*$', '', line, flags=re.I)


def analyse(path):
    with open(path, 'rb') as fh:
        raw = fh.read()

    if any(byte > 127 for byte in raw):
        line_no = raw[:next(i for i, b in enumerate(raw) if b > 127)].count(b'\n') + 1
        report('error', path, line_no, 'non-ASCII byte (the file must stay ASCII)')

    if b'\r\n' in raw and raw.replace(b'\r\n', b'').count(b'\n') > 0:
        report('warning', path, 0, 'mixed line endings (CRLF and LF in one file)')

    text = raw.decode('utf-8', errors='replace')
    lines = [line.rstrip('\r\n') for line in text.split('\n')]

    labels = {}
    gotos = []
    defined = set()
    in_block = False
    block_start = 0
    defined_vars = set()

    for index, line in enumerate(lines, start=1):
        stripped = line.strip()

        # --- ( ) > file blocks ------------------------------------------
        if not in_block and stripped.startswith('('):
            in_block = True
            block_start = index
        elif in_block and stripped.startswith(')'):
            in_block = False
            if not re.match(r'^\)\s*(>>?)\s*("[^"]*"|\S+)\s*$', stripped):
                report('error', path, index, 'block does not end with ") > "file""')
        elif in_block:
            if stripped.startswith('::'):
                report('error', path, index, 'a "::" comment inside a ( ) block breaks the block')
            if stripped.lower().startswith('echo') and not stripped.lower().startswith('echo.'):
                body = stripped[4:]
                for char in '()|&<>':
                    for match in re.finditer(re.escape(char), body):
                        before = body[:match.start()]
                        if before.endswith('^'):
                            continue
                        report('error', path, index,
                               'raw "%s" inside echo in a block - escape it as ^%s' % (char, char))
                        break

        # --- labels ------------------------------------------------------
        if stripped.startswith(':') and not stripped.startswith('::'):
            name = stripped[1:].split()[0].lower()
            if name in labels:
                report('error', path, index, 'duplicate label ":%s" (first at line %d)' % (name, labels[name]))
            labels[name] = index
            if index < len(lines):
                nxt = lines[index].strip()
                if nxt.startswith('('):
                    report('warning', path, index + 1, 'label followed directly by "("')

        # --- goto / call --------------------------------------------------
        for match in re.finditer(r'\b(goto|call)\s+(:)?([A-Za-z0-9_\-]+)', strip_comment(line), re.I):
            verb, colon, name = match.group(1).lower(), match.group(2), match.group(3).lower()
            if verb == 'call' and not colon:
                continue    # call "some file.bat"
            gotos.append((name, index))

        # --- variables ----------------------------------------------------
        for match in re.finditer(r'set\s+"?([A-Za-z_][A-Za-z0-9_]*)=', strip_comment(line), re.I):
            defined_vars.add(match.group(1).lower())
        if not stripped.lower().startswith('rem') and not stripped.startswith('::'):
            for match in re.finditer(r'%([^%\s]+)%', line):
                name = match.group(1).lower()
                if name not in PSEUDO_VARS:
                    defined.add((name, index))

        # --- quoting ------------------------------------------------------
        if not stripped.lower().startswith('rem') and not stripped.startswith('::'):
            if line.count('"') % 2 == 1:
                report('error', path, index, 'odd number of double quotes')

    for name, index in gotos:
        if name not in labels:
            report('error', path, index, 'jump to undefined label ":%s"' % name)

    return defined_vars, defined


def main():
    settings_vars = set()
    settings_path = os.path.join(SERVER, 'settings.bat')
    if os.path.exists(settings_path):
        with open(settings_path, encoding='utf-8') as fh:
            for line in fh:
                for match in re.finditer(r'set\s+"?([A-Za-z_][A-Za-z0-9_]*)=', line, re.I):
                    settings_vars.add(match.group(1).lower())

    batches = sorted(glob.glob(os.path.join(SERVER, '*.bat')))
    for path in batches:
        local_vars, used = analyse(path)
        known = local_vars | settings_vars
        for name, index in sorted(used):
            if name not in known:
                report('error', path, index, '%%%s%% is never defined' % name)

    print('Checked %d batch files.' % len(batches))
    for warning in warnings:
        print('WARNING ' + warning)
    for error in errors:
        print('ERROR   ' + error)

    if errors:
        print('\n%d error(s), %d warning(s)' % (len(errors), len(warnings)))
        return 1
    print('No problems found (%d warning(s)).' % len(warnings))
    return 0


if __name__ == '__main__':
    sys.exit(main())
