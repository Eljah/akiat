#!/usr/bin/env python3
"""Convert rough ASR SRT to editable CSV. Review every row and remove interviewer turns."""
from __future__ import annotations
import argparse, csv, re
from pathlib import Path

def t2sec(x):
    h,m,s= x.replace(',','.').split(':');return round(int(h)*3600+int(m)*60+float(s),3)

def convert(input_path,output_path):
    data=input_path.read_text(encoding='utf-8-sig')
    blocks=re.split(r'\n\s*\n',data.strip())
    with output_path.open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.writer(f);w.writerow(['start','end','text'])
        count=0
        for block in blocks:
            lines=block.splitlines()
            time_index=next((i for i,s in enumerate(lines) if '-->' in s),None)
            if time_index is None:
                continue
            match=re.search(r'(\d\d:\d\d:\d\d[,.]\d+)\s*-->\s*(\d\d:\d\d:\d\d[,.]\d+)',lines[time_index])
            if not match:
                continue
            words=' '.join(s.strip() for s in lines[time_index+1:])
            words=re.sub(r'<[^>]+>','',words)
            if not words:
                continue
            w.writerow([t2sec(match.group(1)),t2sec(match.group(2)),words]);count+=1
    print(f'Wrote {count} draft segments; manually correct texts and time boundaries before training.')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('output',type=Path)
    a=p.parse_args();convert(a.source,a.output)
