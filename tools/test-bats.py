#!/usr/bin/env python3
"""
Tests for the Windows .bat scripts of this server pack.

There is no Windows/cmd.exe in this environment, so this file contains:
  * a small interpreter for the subset of batch used by the pack
    (labels, goto, call, if exist / if "a"=="b" / if errorlevel, set,
     echo with redirection, ( ) > file blocks, exit /b, choice,
     findstr, where, pause, timeout, cd)
  * mock implementations of powershell, java, choice and the network,
    so every branch of start.bat / update.bat / backup.bat can be executed
    and its side effects checked.

Run:  python3 tools/test-bats.py
"""

import os
import re
import shutil
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVER_DIR = os.path.join(REPO, 'server')

MOCK_JAVA_OUTPUT = [
    'Starting minecraft server version 26.3',
    'Preparing level "world"',
    'Done (12.345s)! For help, type "help"',
]


class MockNetwork:
    """Fakes the PaperMC API and the jar download."""

    def __init__(self, ok=True):
        self.ok = ok
        self.downloads = []


class MockSystem:
    def __init__(self):
        self.network = MockNetwork()
        self.java_exit_code = 0
        self.java_calls = []
        self.java_installed = True
        self.java_major = 25
        self.powershell_available = True
        self.choice_answers = []
        self.max_java_runs = 999
        self.prompts = []
        self.transcript = []


def tokenize(line):
    """Windows-ish tokenizer: keeps quotes, splits on spaces/tabs."""
    tokens, cur, in_quotes = [], '', False
    for ch in line:
        if ch == '"':
            in_quotes = not in_quotes
            cur += ch
        elif ch in ' \t' and not in_quotes:
            if cur:
                tokens.append(cur)
                cur = ''
        else:
            cur += ch
    if cur:
        tokens.append(cur)
    return tokens


def unquote(token):
    token = token.strip()
    if len(token) >= 2 and token[0] == '"' and token[-1] == '"':
        return token[1:-1]
    return token


class BatchError(Exception):
    pass


class Batch:
    """Interprets a .bat file with the help of MockSystem."""

    def __init__(self, path, mock, vars=None, log=None):
        self.path = os.path.abspath(path)
        self.script_dir = os.path.dirname(self.path)
        self.mock = mock
        self.vars = dict(vars or {})
        self.log = log if log is not None else []
        with open(self.path, 'r', encoding='utf-8', errors='replace') as fh:
            raw = fh.read()
        self.lines = [line.rstrip('\r\n') for line in raw.split('\n')]
        self.labels = {}
        for i, line in enumerate(self.lines):
            stripped = line.strip()
            if stripped.startswith(':') and not stripped.startswith('::'):
                self.labels[stripped[1:].split()[0].lower()] = i
        self.cwd = os.path.dirname(self.path)
        self.errorlevel = 0
        self.stopped = False

    # ---------- helpers -------------------------------------------------
    def out(self, text=''):
        print(text)
        self.mock.transcript.append(str(text))

    def note(self, text):
        self.log.append(text)
        self.mock.transcript.append(text)

    def expand(self, text):
        def repl(match):
            key = match.group(1)
            low = key.lower()
            if low == 'errorlevel':
                return str(self.errorlevel)
            if low == 'cd':
                return self.cwd
            if key == '~dp0':
                return self.script_dir + '\\'
            if low in self.vars:
                return str(self.vars[low])
            self.note('  <undefined var %%{0}%% -> empty>'.format(key))
            return ''
        # cmd expands the batch-argument modifiers (%~dp0 ...) before
        # ordinary %VAR% references, so do the same here.
        text = re.sub(r'%~dp0', lambda m: self.script_dir + '\\', text, flags=re.I)
        result = re.sub(r'%([^%]+)%', repl, text)
        # ^ escapes the next character (used for parentheses in echo)
        return re.sub(r'\^(.)', r'\1', result)

    def win_path(self, path):
        return path.replace('\\', '/')

    # ---------- execution ----------------------------------------------
    def run(self, entry_label=None, max_steps=20000):
        pc = 0
        if entry_label:
            pc = self.labels.get(entry_label.lower(), 0)
        call_stack = []
        steps = 0
        while pc < len(self.lines) and steps < max_steps:
            steps += 1
            line = self.lines[pc]
            pc += 1
            stripped = line.strip()
            if not stripped or stripped.lower() == '@echo off' or stripped.startswith(':') \
                    or stripped.lower().startswith('rem ') or stripped.lower() == 'rem':
                continue

            # ( ... ) > "file"  statement block
            if stripped == '(' or stripped.startswith('('):
                block, redirect, extra = [], None, stripped[1:]
                if extra:
                    block.append(extra)
                depth = 1
                while pc < len(self.lines):
                    inner = self.lines[pc].strip()
                    pc += 1
                    if inner.startswith(')'):
                        redirect = inner[1:].strip()
                        depth -= 1
                        break
                    block.append(inner)
                if redirect:
                    target, append = self.parse_redirect(redirect)
                    out_lines = []
                    for inner in block:
                        if not inner or inner.lower().startswith('rem '):
                            continue
                        if inner.lower().startswith('echo'):
                            text = inner[4:]
                            if text.startswith('.'):
                                text = ''
                            out_lines.append(self.expand(text).lstrip())
                        else:
                            raise BatchError('unsupported statement in block: ' + inner)
                    path = self.win_path(target)
                    if path.lower() != 'nul':
                        mode = 'a' if append else 'w'
                        with open(path, mode, encoding='utf-8') as fh:
                            fh.write('\n'.join(out_lines) + '\n')
                    self.errorlevel = 0
                continue

            stripped = self.expand(stripped)
            tokens = tokenize(stripped)
            cmd = unquote(tokens[0]).lower() if tokens else ''
            arg_text = stripped[len(tokens[0]):].strip() if tokens else ''

            if cmd in ('setlocal', 'endlocal'):
                continue
            if cmd == 'cd':
                target = unquote(arg_text.split(' ', 1)[-1] if '/d' in arg_text.lower() else arg_text)
                self.cwd = self.win_path(target)
                os.makedirs(self.cwd, exist_ok=True)
                os.chdir(self.cwd)
                continue
            if cmd == 'set':
                assignment = unquote(arg_text).strip()
                if '=' not in assignment:
                    continue
                key, value = assignment.split('=', 1)
                self.vars[key.strip().lower()] = self.expand(value)
                continue
            if cmd == 'echo' or stripped.lower().startswith('echo.'):
                text = stripped[4:]
                if text.lower() == 'off' and not arg_text:
                    continue
                target, append = self.parse_redirect(text)
                if target:
                    text = text[:text.lower().rfind('>')]
                if text.startswith('.'):
                    text = ''
                out = self.expand(text).lstrip()
                if target and self.win_path(target).lower() != 'nul':
                    mode = 'a' if append else 'w'
                    with open(self.win_path(target), mode, encoding='utf-8') as fh:
                        fh.write(out + '\n')
                else:
                    self.out(out)
                self.errorlevel = 0
                continue
            if cmd == 'goto':
                label = unquote(arg_text).lstrip(':').strip().lower()
                if label not in self.labels:
                    raise BatchError('goto to unknown label: ' + label)
                pc = self.labels[label]
                continue
            if cmd == 'call':
                arg = arg_text.strip()
                if arg.startswith(':'):
                    label = arg[1:].strip().lower()
                    if label not in self.labels:
                        raise BatchError('call to unknown label: ' + label)
                    call_stack.append(pc)
                    pc = self.labels[label]
                else:
                    target = self.win_path(unquote(arg))
                    nested = Batch(target, self.mock, self.vars, self.log)
                    nested.cwd = self.cwd
                    code = nested.run()
                    self.vars = nested.vars
                    self.errorlevel = code
                continue
            if cmd == 'exit':
                sub = unquote(arg_text.split()[0]) if arg_text.split() else ''
                code = self.errorlevel
                parts = self.expand(arg_text).split()
                if len(parts) >= 2 and parts[1].strip().isdigit():
                    code = int(parts[1])
                if sub.lower() == '/b':
                    if call_stack:
                        pc = call_stack.pop()
                        self.errorlevel = code
                        continue
                    return code
                return code
            if cmd == 'if':
                taken, rest = self.eval_if(arg_text)
                if taken:
                    if rest:
                        try:
                            self.exec_inline(rest)
                        except GotoSignal as jump:
                            pc = jump.pc
                continue
            if cmd == 'where':
                target = unquote(arg_text.split()[0]) if arg_text.split() else ''
                ok = self.mock.powershell_available and target.lower() == 'powershell'
                self.errorlevel = 0 if ok else 1
                continue
            if cmd == 'findstr':
                self.exec_findstr(arg_text)
                continue
            if cmd == 'choice':
                self.exec_choice(arg_text)
                continue
            if cmd in ('pause', 'timeout'):
                if cmd == 'pause':
                    self.out('Press any key to continue . . .')
                self.errorlevel = 0
                continue
            if cmd == 'powershell' or cmd == 'powershell.exe':
                self.exec_powershell(arg_text)
                continue
            if cmd == 'start':
                self.errorlevel = 0
                continue
            # java.exe / java
            first = unquote(tokens[0])
            if os.path.basename(first).lower() in ('java', 'java.exe'):
                self.exec_java(tokens)
                if self.stopped:
                    return self.errorlevel
                continue
            raise BatchError('unsupported command: ' + stripped)

        if steps >= max_steps:
            raise BatchError('step limit reached (infinite loop?)')
        return self.errorlevel

    def exec_inline(self, text):
        """Executes a single statement in the current context."""
        stripped = text.strip()
        tokens = tokenize(stripped)
        cmd = unquote(tokens[0]).lower()
        arg = stripped[len(tokens[0]):].strip()
        if cmd == 'goto':
            label = unquote(arg).lstrip(':').strip().lower()
            if label not in self.labels:
                raise BatchError('goto to unknown label: ' + label)
            raise GotoSignal(self.labels[label])
        if cmd == 'set':
            assignment = unquote(arg)
            key, value = assignment.split('=', 1)
            self.vars[key.strip().lower()] = self.expand(value)
            return
        raise BatchError('unsupported inline statement: ' + stripped)

    def parse_redirect(self, text):
        """Finds > / >> in a statement; returns (target, append)."""
        match = re.search(r'(>>?)\s*("[^"]*"|\S+)', text)
        if not match:
            return None, False
        return unquote(match.group(2)), match.group(1) == '>>'

    def eval_if(self, arg_text):
        rest = self.expand(arg_text)
        negate = False
        insensitive = False
        if rest[:3].lower() == '/i ':
            insensitive = True
            rest = rest[3:].strip()
        if rest[:4].lower() == 'not ':
            negate = True
            rest = rest[4:].strip()

        if rest.lower().startswith('exist '):
            rest = rest[6:].strip()
            tokens = tokenize(rest)
            path = unquote(tokens[0])
            value = os.path.exists(self.win_path(path))
            remainder = rest[len(tokens[0]):].strip()
        elif rest.lower().startswith('errorlevel '):
            rest = rest[11:].strip()
            num = int(re.findall(r'\d+', rest)[0])
            value = self.errorlevel >= num
            remainder = rest[rest.index(str(num)) + len(str(num)):].strip()
        else:
            match = re.match(r'"([^"]*)"\s*==\s*"?([^"]*)"?\s*(.*)', rest)
            if not match:
                raise BatchError('unsupported if: ' + arg_text)
            left, right, remainder = match.group(1), match.group(2), match.group(3)
            if insensitive:
                value = left.lower() == right.lower()
            else:
                value = left == right
        if negate:
            value = not value
        return value, remainder

    def exec_findstr(self, arg_text):
        match = re.search(r'/c:"([^"]*)"\s*"?([^">]*)', arg_text, re.I)
        pattern = match.group(1)
        target = unquote(match.group(2).strip())
        path = self.win_path(target)
        if not os.path.exists(path):
            self.errorlevel = 1
            return
        with open(path, encoding='utf-8', errors='replace') as fh:
            content = fh.read()
        found = pattern.lower() in content.lower()
        self.errorlevel = 0 if found else 1

    def exec_choice(self, arg_text):
        match = re.search(r'/m\s+"([^"]*)"', arg_text)
        message = match.group(1) if match else ''
        default = re.search(r'/d\s+([A-Za-z])', arg_text)
        answer = self.mock.choice_answers.pop(0) if self.mock.choice_answers \
            else (default.group(1) if default else 'N')
        self.out(message + answer)
        self.errorlevel = 1 if answer.upper() == 'Y' else 2

    def exec_powershell(self, arg_text):
        if not self.mock.powershell_available:
            self.errorlevel = 9009
            return
        script_match = re.search(r'-File\s+"?([^"]+\.ps1)"?', arg_text, re.I)
        script = os.path.basename(self.win_path(script_match.group(1))) if script_match else ''
        if script == 'check-java.ps1':
            if not self.mock.java_installed:
                self.out('      [X] Java was not found')
                self.errorlevel = 3
            elif self.mock.java_major < 25:
                self.out('      [X] Java {0} is older than the required Java 25.'.format(self.mock.java_major))
                self.errorlevel = 2
            else:
                self.out('      java: C:\\Program Files\\Java\\jdk-25\\bin\\java.exe')
                self.out('      openjdk version "25.0.1"')
                self.out('      OK: Java 25.')
                self.errorlevel = 0
            return
        if script == 'download-paper.ps1':
            dest_match = re.search(r'-Destination\s+"([^"]+)"', arg_text)
            dest = self.win_path(dest_match.group(1)) if dest_match else None
            if not self.mock.network.ok:
                self.out('[X] Could not reach the PaperMC API.')
                self.errorlevel = 1
                return
            self.out('[core] Latest build: 151 [BETA] - paper-26.3-151.jar (51.5 MB)')
            self.out('[core] Downloading https://fill-data.papermc.io/...')
            self.mock.network.downloads.append(dest)
            if dest:
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                if os.path.exists(dest):
                    if os.path.exists(dest + '.old'):
                        os.remove(dest + '.old')
                    os.rename(dest, dest + '.old')
                    print('[core] Previous core kept as paper.jar.old')
                with open(dest, 'w', encoding='utf-8') as fh:
                    fh.write('FAKE PAPER JAR v151\n')
            self.errorlevel = 0
            return
        if script == 'backup.ps1':
            source = re.search(r'-Source\s+"([^"]+)"', arg_text)
            target = re.search(r'-Destination\s+"([^"]+)"', arg_text)
            src = self.win_path(source.group(1)) if source else self.cwd
            dst = self.win_path(target.group(1)) if target else os.path.join(src, 'backups')
            items = [name for name in os.listdir(src) if name.startswith('world')]
            if not items:
                self.out('[X] Nothing to back up in ' + src)
                self.errorlevel = 1
                return
            os.makedirs(dst, exist_ok=True)
            out = os.path.join(dst, 'backup-2026-10-04_12-00-00.zip')
            with open(out, 'w', encoding='utf-8') as fh:
                fh.write('FAKE ZIP: ' + ', '.join(items) + '\n')
            self.out('[backup] Done: backup-2026-10-04_12-00-00.zip (0.1 MB)')
            self.errorlevel = 0
            return
        self.out('powershell: unknown script ' + script)
        self.errorlevel = 1

    def exec_java(self, tokens):
        argv = [unquote(t) for t in tokens]
        self.mock.java_calls.append(argv)
        if not self.mock.java_installed:
            self.out("'java' is not recognized as an internal or external command")
            self.errorlevel = 9009
            return
        for line in MOCK_JAVA_OUTPUT:
            self.out(line)
        self.errorlevel = self.mock.java_exit_code
        if len(self.mock.java_calls) >= self.mock.max_java_runs:
            self.stopped = True   # end of the test: stop the restart loop


class GotoSignal(Exception):
    def __init__(self, pc):
        self.pc = pc


# ---------------------------------------------------------------------------
# Test harness
# ---------------------------------------------------------------------------

class Scenario:
    def __init__(self, name):
        self.name = name
        self.actions = []
        self.checks = []

    def action(self, description, fn):
        self.actions.append((description, fn))
        return self

    def check(self, description, fn):
        self.checks.append((description, fn))
        return self


def load_settings_file(path):
    """Reads settings.bat the way cmd would, so tests can tweak values."""
    variables = {}
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            match = re.match(r'\s*set\s+"([^"]+)=([^"]*)"', line, re.I)
            if match:
                variables[match.group(1).strip().lower()] = match.group(2)
    return variables


def run_scenario(scenario):
    tmp = tempfile.mkdtemp(prefix='mcbat-')
    workdir = os.path.join(tmp, 'server')
    shutil.copytree(SERVER_DIR, workdir)
    mock = MockSystem()
    print('\n' + '=' * 70)
    print('SCENARIO: ' + scenario.name)
    print('  workdir: ' + workdir)
    print('=' * 70)
    for description, fn in scenario.actions:
        print('  -- ' + description)
        fn(workdir, mock)
    cwd_backup = os.getcwd()
    os.chdir(workdir)
    batch = Batch(os.path.join(workdir, 'start.bat'), mock)
    try:
        code = batch.run()
        crashed = None
    except Exception as exc:      # BatchError and friends
        code = None
        crashed = '%s: %s' % (type(exc).__name__, exc)
    finally:
        os.chdir(cwd_backup)

    failures = []
    for description, fn in scenario.checks:
        try:
            ok = fn(workdir, mock, code, crashed)
        except Exception as exc:
            ok = False
            description += ' (check raised %s: %s)' % (type(exc).__name__, exc)
        print('    [%s] %s' % ('PASS' if ok else 'FAIL', description))
        if not ok:
            failures.append(description)
    shutil.rmtree(tmp, ignore_errors=True)
    return failures, crashed


def main():
    failures_total = []
    crashed_total = []

    # 1. Happy path: fresh folder, java ok, network ok, no restart
    def happy_actions(workdir, mock):
        pass

    happy = Scenario('fresh install, everything works')
    happy.check('exit code is 0', lambda w, m, c, cr: c == 0)
    happy.check('no interpreter errors', lambda w, m, c, cr: cr is None)
    happy.check('paper.jar was downloaded', lambda w, m, c, cr: os.path.exists(os.path.join(w, 'paper.jar')))
    happy.check('eula.txt created with eula=true',
                lambda w, m, c, cr: 'eula=true' in open(os.path.join(w, 'eula.txt'), encoding='utf-8').read().lower())
    happy.check('java was started once with -jar paper.jar --nogui',
                lambda w, m, c, cr: len(m.java_calls) == 1 and
                os.path.basename(m.java_calls[0][0]) in ('java', 'java.exe') and
                '-jar' in m.java_calls[0] and
                m.java_calls[0][m.java_calls[0].index('-jar') + 1].endswith('paper.jar') and
                m.java_calls[0][-1] == '--nogui')
    happy.check('memory flags come from settings.bat',
                lambda w, m, c, cr: '-Xmx4G' in m.java_calls[0] and '-Xms4G' in m.java_calls[0])
    happy.check('settings.bat was loaded (PAPER_VERSION used in output)',
                lambda w, m, c, cr: any('Paper 26.3 server' in line for line in m.transcript))
    failures_total += run_scenario(happy)[0]

    # 2. Java too old
    def old_java(workdir, mock):
        mock.java_major = 21

    scen = Scenario('Java 21 installed (too old)')
    scen.action('pretend java 21 is installed', old_java)
    scen.check('exit code is 1', lambda w, m, c, cr: c == 1)
    scen.check('the server was not started', lambda w, m, c, cr: not m.java_calls)
    scen.check('the user is told to install Java 25',
               lambda w, m, c, cr: any('JAVA IS TOO OLD' in line for line in m.transcript))
    failures_total += run_scenario(scen)[0]

    # 3. Java missing entirely
    def no_java(workdir, mock):
        mock.java_installed = False

    scen = Scenario('Java is not installed')
    scen.action('pretend java is missing', no_java)
    scen.check('exit code is 1', lambda w, m, c, cr: c == 1)
    scen.check('adoptium link is shown',
               lambda w, m, c, cr: any('adoptium.net' in line for line in m.transcript))
    scen.check('no server process was started', lambda w, m, c, cr: not m.java_calls)
    failures_total += run_scenario(scen)[0]

    # 4. EULA not accepted
    def eula_false(workdir, mock):
        with open(os.path.join(workdir, 'eula.txt'), 'w', encoding='utf-8') as fh:
            fh.write('#Minecraft EULA\neula=false\n')

    scen = Scenario('eula.txt says false')
    scen.action('write eula=false', eula_false)
    scen.check('exit code is 1', lambda w, m, c, cr: c == 1)
    scen.check('the EULA is explained',
               lambda w, m, c, cr: any('eula=true' in line for line in m.transcript)
               and any('MinecraftEULA' in line for line in m.transcript))
    scen.check('no server process was started', lambda w, m, c, cr: not m.java_calls)
    failures_total += run_scenario(scen)[0]

    # 5. Download of the core fails
    def no_network(workdir, mock):
        mock.network.ok = False

    scen = Scenario('PaperMC API unreachable, no local core')
    scen.action('pretend there is no internet', no_network)
    scen.check('exit code is 1', lambda w, m, c, cr: c == 1)
    scen.check('manual download instructions are printed',
               lambda w, m, c, cr: any('papermc.io/downloads/paper' in line for line in m.transcript))
    scen.check('no paper.jar was created', lambda w, m, c, cr: not os.path.exists(os.path.join(w, 'paper.jar')))
    failures_total += run_scenario(scen)[0]

    # 6. Existing core: no download, eula accepted, restart prompt answered "no"
    def existing_core(workdir, mock):
        with open(os.path.join(workdir, 'paper.jar'), 'w', encoding='utf-8') as fh:
            fh.write('FAKE PAPER JAR\n')
        mock.choice_answers = ['N']

    scen = Scenario('paper.jar already present, user declines restart')
    scen.action('place an existing paper.jar, answer N on the restart prompt', existing_core)
    scen.check('exit code is 0', lambda w, m, c, cr: c == 0)
    scen.check('the core was not downloaded again', lambda w, m, c, cr: not m.network.downloads)
    scen.check('eula.txt was created', lambda w, m, c, cr: os.path.exists(os.path.join(w, 'eula.txt')))
    scen.check('the restart prompt was shown',
               lambda w, m, c, cr: any('Restart the server?' in line for line in m.transcript))
    scen.check('the server ran exactly once', lambda w, m, c, cr: len(m.java_calls) == 1)
    failures_total += run_scenario(scen)[0]

    # 7. AUTO_RESTART=yes restarts after a stop (checked by answering "stop" after one run)
    def auto_restart(workdir, mock):
        settings_path = os.path.join(workdir, 'settings.bat')
        with open(settings_path, encoding='utf-8') as fh:
            content = fh.read()
        content = content.replace('set "AUTO_RESTART=ask"', 'set "AUTO_RESTART=no"')
        with open(settings_path, 'w', encoding='utf-8') as fh:
            fh.write(content)

    scen = Scenario('AUTO_RESTART=no exits after the server stops')
    scen.action('set AUTO_RESTART=no', auto_restart)
    scen.check('exit code is 0', lambda w, m, c, cr: c == 0)
    scen.check('server started once', lambda w, m, c, cr: len(m.java_calls) == 1)
    scen.check('no restart prompt', lambda w, m, c, cr: not any('Restart the server?' in line for line in m.transcript))
    failures_total += run_scenario(scen)[0]

    # 8. Custom settings are honoured
    def custom_settings(workdir, mock):
        settings_path = os.path.join(workdir, 'settings.bat')
        with open(settings_path, encoding='utf-8') as fh:
            content = fh.read()
        content = content.replace('set "XMS=4G"', 'set "XMS=2G"')
        content = content.replace('set "XMX=4G"', 'set "XMX=6G"')
        content = content.replace('set "PAPER_VERSION=26.3"', 'set "PAPER_VERSION=26.2"')
        with open(settings_path, 'w', encoding='utf-8') as fh:
            fh.write(content)

    scen = Scenario('custom memory/version in settings.bat')
    scen.action('set XMS=2G, XMX=6G, PAPER_VERSION=26.2', custom_settings)
    scen.check('memory flags are -Xms2G -Xmx6G',
               lambda w, m, c, cr: '-Xms2G' in m.java_calls[0] and '-Xmx6G' in m.java_calls[0])
    scen.check('version 26.2 is reported',
               lambda w, m, c, cr: any('Paper 26.2 server' in line for line in m.transcript))
    failures_total += run_scenario(scen)[0]

    # 9. backup.bat
    scen = Scenario('backup.bat with a world present')
    scen.action('create a fake world folder',
                lambda w, m: os.makedirs(os.path.join(w, 'world', 'region'), exist_ok=True))
    scen.action('create a fake paper.jar',
                lambda w, m: open(os.path.join(w, 'paper.jar'), 'w', encoding='utf-8').write('FAKE\n'))
    tmp = tempfile.mkdtemp(prefix='mcbackup-')
    workdir = os.path.join(tmp, 'server')
    shutil.copytree(SERVER_DIR, workdir)
    mock = MockSystem()
    print('\n' + '=' * 70)
    print('SCENARIO: ' + scen.name)
    print('=' * 70)
    for description, fn in scen.actions:
        print('  -- ' + description)
        fn(workdir, mock)
    cwd_backup = os.getcwd()
    os.chdir(workdir)
    batch = Batch(os.path.join(workdir, 'backup.bat'), mock)
    try:
        code = batch.run()
        crashed = None
    except Exception as exc:
        code, crashed = None, '%s: %s' % (type(exc).__name__, exc)
    finally:
        os.chdir(cwd_backup)
    scen.check('exit code is 0', lambda w, m, c, cr: c == 0 and cr is None)
    scen.check('a backup archive was created',
               lambda w, m, c, cr: any(name.startswith('backup-') and name.endswith('.zip')
                                       for name in os.listdir(os.path.join(w, 'backups'))))
    scen.check('the world folder was included',
               lambda w, m, c, cr: 'world' in open(os.path.join(w, 'backups', os.listdir(
                   os.path.join(w, 'backups'))[0]), encoding='utf-8').read())
    for description, fn in scen.checks:
        ok = fn(workdir, mock, code, crashed)
        print('    [%s] %s' % ('PASS' if ok else 'FAIL', description))
        if not ok:
            failures_total.append(description)
    shutil.rmtree(tmp, ignore_errors=True)

    # 10. update.bat replaces an existing core and keeps the old one
    tmp = tempfile.mkdtemp(prefix='mcupdate-')
    workdir = os.path.join(tmp, 'server')
    shutil.copytree(SERVER_DIR, workdir)
    mock = MockSystem()
    print('\n' + '=' * 70)
    print('SCENARIO: update.bat replaces the core and keeps paper.jar.old')
    print('=' * 70)
    with open(os.path.join(workdir, 'paper.jar'), 'w', encoding='utf-8') as fh:
        fh.write('OLD CORE\n')
    cwd_backup = os.getcwd()
    os.chdir(workdir)
    batch = Batch(os.path.join(workdir, 'update.bat'), mock)
    try:
        code = batch.run()
        crashed = None
    except Exception as exc:
        code, crashed = None, '%s: %s' % (type(exc).__name__, exc)
    finally:
        os.chdir(cwd_backup)
    update_checks = [
        ('exit code is 0', lambda w, m, c, cr: c == 0 and cr is None),
        ('the new core was downloaded', lambda w, m, c, cr: len(m.network.downloads) == 1),
        ('the old core is kept as paper.jar.old',
         lambda w, m, c, cr: os.path.exists(os.path.join(w, 'paper.jar.old'))),
        ('paper.jar contains the fresh build',
         lambda w, m, c, cr: 'v151' in open(os.path.join(w, 'paper.jar'), encoding='utf-8').read()),
    ]
    for description, fn in update_checks:
        ok = fn(workdir, mock, code, crashed)
        print('    [%s] %s' % ('PASS' if ok else 'FAIL', description))
        if not ok:
            failures_total.append(description)
    shutil.rmtree(tmp, ignore_errors=True)

    # 11. AUTO_RESTART=yes keeps the server running
    tmp = tempfile.mkdtemp(prefix='mcauto-')
    workdir = os.path.join(tmp, 'server')
    shutil.copytree(SERVER_DIR, workdir)
    mock = MockSystem()
    mock.max_java_runs = 3
    print('\n' + '=' * 70)
    print('SCENARIO: AUTO_RESTART=yes restarts the server')
    print('=' * 70)
    with open(os.path.join(workdir, 'settings.bat'), encoding='utf-8') as fh:
        content = fh.read().replace('set "AUTO_RESTART=ask"', 'set "AUTO_RESTART=yes"')
    with open(os.path.join(workdir, 'settings.bat'), 'w', encoding='utf-8') as fh:
        fh.write(content)
    cwd_backup = os.getcwd()
    os.chdir(workdir)
    batch = Batch(os.path.join(workdir, 'start.bat'), mock)
    try:
        code = batch.run()
        crashed = None
    except Exception as exc:
        code, crashed = None, '%s: %s' % (type(exc).__name__, exc)
    finally:
        os.chdir(cwd_backup)
    auto_checks = [
        ('the server was started three times in a row',
         lambda w, m, c, cr: len(m.java_calls) == 3),
        ('no interpreter errors', lambda w, m, c, cr: cr is None),
        ('the restart notice is printed',
         lambda w, m, c, cr: any('Restarting in 5 seconds' in line for line in m.transcript)),
    ]
    for description, fn in auto_checks:
        ok = fn(workdir, mock, code, crashed)
        print('    [%s] %s' % ('PASS' if ok else 'FAIL', description))
        if not ok:
            failures_total.append(description)
    shutil.rmtree(tmp, ignore_errors=True)

    print('\n' + '=' * 70)
    if failures_total:
        print('FAILED CHECKS: %d' % len(failures_total))
        for item in failures_total:
            print('  - ' + item)
        return 1
    print('ALL BATCH SCENARIOS PASSED')
    return 0


if __name__ == '__main__':
    sys.exit(main())
