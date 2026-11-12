"""Drive real X11 mouse events; the test mod supplies GUI-to-window coordinates."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def main():
    evidence = Path('evidence')
    evidence.mkdir(exist_ok=True)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
    game = subprocess.Popen([java, '-Dhmc.check.xvfb=true', '-jar', 'hmc.jar', '--command', 'launch', sys.argv[1]])
    previous = -1
    deadline = time.monotonic() + 540
    try:
        while game.poll() is None and time.monotonic() < deadline:
            request = Path('run/ui-request.json')
            if not request.exists():
                time.sleep(0.1)
                continue
            action = json.loads(request.read_text())
            if action['id'] == previous:
                time.sleep(0.1)
                continue
            result = subprocess.run(['xdotool', 'search', '--onlyvisible', '--name', 'Minecraft'], capture_output=True, text=True)
            if result.returncode or not result.stdout.splitlines():
                time.sleep(0.1)
                continue
            window = result.stdout.splitlines()[-1]
            assert window.isdigit()
            subprocess.run(['xdotool','windowfocus','--sync',window],check=True)
            if action['id'] in (0,4,6):
                subprocess.run(['import','-window',window,str(evidence / f'native-ui-{action["id"]}.png')],check=True)
            subprocess.run(['xdotool','mousemove','--sync','--window',window,str(action['x']),str(action['y'])],check=True)
            subprocess.run(['xdotool','mousedown','1'],check=True)
            time.sleep(0.15)
            if action['drag']:
                subprocess.run(['xdotool','mousemove','--sync','--window',window,str(action['toX']),str(action['toY'])],check=True)
                time.sleep(0.15)
            subprocess.run(['xdotool','mouseup','1'],check=True)
            time.sleep(0.15)
            Path(f'run/ui-action-done-{action["id"]}').write_text('native event sent\n')
            previous = action['id']
            print('Native mouse action:',previous,flush=True)
        if game.poll() is None:
            raise TimeoutError('native UI game timeout')
        if game.returncode:
            raise RuntimeError(f'game exited with {game.returncode}')
        assert Path('run/ui-smoke.pass').exists(), 'native UI assertions did not pass'
        print(Path('run/ui-smoke.pass').read_text(),flush=True)
    finally:
        if game.poll() is None:
            game.terminate()
            try:
                game.wait(timeout=10)
            except subprocess.TimeoutExpired:
                game.kill()


if __name__ == '__main__':
    main()
