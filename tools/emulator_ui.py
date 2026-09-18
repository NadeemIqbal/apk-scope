import os, shutil, subprocess, sys, xml.etree.ElementTree as ET, re
from pathlib import Path
ADB = os.environ.get('ADB') or shutil.which('adb') or 'adb'
def adb(*args): return subprocess.check_output([ADB,'-s','emulator-5554',*args])
result=adb('shell','uiautomator','dump','/sdcard/spike-window.xml')
if b'UI hierchary dumped' not in result and b'UI hierarchy dumped' not in result:
 raise SystemExit('No current UI hierarchy; use a fresh screenshot instead')
xml=adb('shell','cat','/sdcard/spike-window.xml')
nodes=list(ET.fromstring(xml).iter('node'))
if len(sys.argv)>1 and sys.argv[1]=='tap':
 target=sys.argv[2]
 matches=[n for n in nodes if n.get('text','').casefold()==target.casefold() or n.get('content-desc','').casefold()==target.casefold()]
 if len(matches)!=1: raise SystemExit(f'Expected one match for {target!r}, found {len(matches)}')
 n=matches[0]; x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
 print('Tap:',target,n.get('bounds'))
 adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
else:
 for n in nodes:
  label=n.get('text') or n.get('content-desc')
  if label: print(label, n.get('bounds'))
 if len(sys.argv)>1:
  path=Path(sys.argv[1]);path.with_suffix('.xml').write_bytes(xml);path.with_suffix('.png').write_bytes(adb('exec-out','screencap','-p'))
