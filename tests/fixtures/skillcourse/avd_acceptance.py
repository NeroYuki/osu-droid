"""Small UI driver for the original fixtures on the single acceptance AVD.

Run from the repository root. Requires ADB access; does not enable network or clear data.
Native canvas coordinates are for the Pixel_8 landscape fixture (2400 x 1080).
Android course dialogs are located by their accessibility resource IDs.
"""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


class Avd:
    def __init__(self, adb, serial):
        self.adb, self.serial = adb, serial

    def command(self, *args):
        return subprocess.check_output(
            [self.adb, '-s', self.serial, *map(str, args)], text=True, timeout=45
        )

    def tap(self, x, y):
        self.command('shell', 'input', 'tap', x, y)

    def click(self, node):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
        assert x2 > x1 and y2 > y1 and node.attrib.get('enabled') == 'true'
        self.tap((x1+x2)//2, (y1+y2)//2)

    def hierarchy(self):
        self.command('shell', 'uiautomator', 'dump', '/sdcard/skill-course-ui.xml')
        return ET.fromstring(self.command('shell', 'cat', '/sdcard/skill-course-ui.xml'))

    @staticmethod
    def find(tree, resource):
        return next((n for n in tree.iter('node') if n.attrib.get('resource-id', '').endswith(':id/'+resource)), None)

    def start(self, name, preflight_only=False, view_only=False, action=None):
        tree = self.hierarchy()
        if self.find(tree, 'skill_course_manager_list') is None:
            self.tap(2200, 55)
            time.sleep(.4)
        if self.find(self.hierarchy(), 'skill_course_manager_list') is None:
            raise RuntimeError('Open Solo song selection before using this driver.')
        for _ in range(24):
            tree = self.hierarchy()
            card = next((n for n in tree.iter('node') if n.attrib.get('resource-id', '').endswith(':id/skill_course_item_root')
                         and any(c.attrib.get('text') == name for c in n.iter('node'))), None)
            resource = 'skill_course_item_'+action if action else ('skill_course_item_view' if view_only else 'skill_course_item_play')
            if card is not None and self.find(card, resource) is not None:
                button = self.find(card, resource)
                if not view_only and action is None:
                    assert button.attrib['text'] == 'Play course', button.attrib['text']
                self.click(button)
                break
            visible = [n.attrib['text'] for n in tree.iter('node') if n.attrib.get('resource-id', '').endswith(':id/skill_course_item_name')]
            upward = visible and name < visible[0]
            self.command('shell', 'input', 'swipe', 1800, 550 if upward else 850, 1800, 850 if upward else 550, 300)
        else:
            raise RuntimeError('Course not found: '+name)
        time.sleep(.4)
        if action:
            print(action+': '+name)
            return
        if view_only:
            print('Viewing: '+name)
            return
        if not preflight_only:
            button = self.find(self.hierarchy(), 'skill_course_preflight_start')
            assert button is not None
            self.click(button)
        print(('Preflight: ' if preflight_only else 'Started: ')+name)

    def screenshot(self, destination):
        self.command('shell', 'screencap', '-p', '/sdcard/skill-course-evidence.png')
        self.command('pull', '/sdcard/skill-course-evidence.png', destination)
        print(destination)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', default=r'E:\dev\scrcpy-win64-v3.0\adb.exe')
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--course')
    parser.add_argument('--preflight', action='store_true')
    parser.add_argument('--view', action='store_true')
    parser.add_argument('--action', choices=['membership', 'more'])
    parser.add_argument('--screenshot')
    parser.add_argument('--wait', type=float, default=0)
    args = parser.parse_args()
    assert 0 <= args.wait <= 30
    avd = Avd(args.adb, args.serial)
    if args.course:
        avd.start(args.course, args.preflight, args.view, args.action)
    time.sleep(args.wait)
    if args.screenshot:
        avd.screenshot(str(Path(args.screenshot)))
