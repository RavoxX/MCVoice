#!/usr/bin/env python3
"""Real SVC receiver survives a second synthetic speaker disconnecting/rejoining."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def main():
    minecraft, classpath = sys.argv[1:]
    processes = []
    files = []
    def start(name, receive=False):
        label = name + ('-receiver' if receive else '-'+str(len(processes)))
        output = open('../../'+label+'.json', 'w')
        log = open('../../'+label+'.log', 'w')
        files.extend([output, log])
        command = ['node', 'bot.js', '127.0.0.1', '25565', minecraft, '--', 'java',
                   '-Dprobe.name='+name, '-Dprobe.durationMs='+('22000' if receive else '5000'),
                   '-Dprobe.receiveOnly='+str(receive).lower(), '-cp', classpath,
                   'dev.mcvoice.client.svc.tools.SvcProbe', '127.0.0.1', '60']
        process = subprocess.Popen(command, stdout=output, stderr=log,
                                   env=dict(os.environ, MCVOICE_PROBE_NAME=name))
        processes.append(process)
        return process, Path('../../'+label+'.log'), Path('../../'+label+'.json')
    try:
        receiver, log, result = start('mcvoice_probe', True)
        deadline = time.monotonic()+60
        while 'udp: CONNECTED' not in log.read_text():
            if receiver.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('receiver did not connect')
            time.sleep(.2)
        for _ in range(2):
            sender, _, output = start('mcvoice_sender')
            if sender.wait(timeout=70) != 0 or not json.loads(output.read_text())['ok']:
                raise RuntimeError('sender did not complete a healthy session')
            time.sleep(1)
        if receiver.wait(timeout=70) != 0:
            raise RuntimeError('receiver rejected ordinary sender reconnection; inspect wire metadata')
        print(result.read_text().strip())
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate(); process.wait(timeout=10)
        for file in files: file.close()


if __name__ == '__main__': main()
