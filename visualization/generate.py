import argparse
import os, sys
import glob
import json
import functools 

HTML_BODY = (
    '<html>'
    '<head>\n'
    '<link rel="stylesheet" href="styles.css">'
    '<meta charset="utf-8">'
    '</head>\n'
    '<body>\n'
    '<main>'
        '<h1>{title}</h1>'
        '<div class="lanes">'
        '{purposes}'
        '{data}'
        '{recipients}'
        '</div>'
        '{composed}'
    '</main>'
    '</body></html>'
)


def parse_purpose_files(folder):
    files = glob.glob(folder + "*.json")
    purposes = []
    
    for file in files:
        with open(file) as json_file:
            purposes.append(json.load(json_file))
    
    i = 1
    result = []
    for item in purposes:
        if len(item.get('data')) > 0:
            item['id'] = i
            i = i + 1
            result.append(item)
    for item in result:
        item['purposes'] = list(filter(lambda p: len(p.get('data')) > 0, item.get('purposes')))
        for cp in item.get('purposes'):
            cp['id'] = i
            i = i + 1
    return result


def render_purpose_data_html(data):
    names = list(map(lambda d: d.split('.')[-1], data))
    return '[' + ', '.join(names) + ']';
    
def render_purpose_dr_html(dr):
    names = list(map(lambda dr: dr.get('recipientId'), dr))
    return '[' + ', '.join(names) + ']';

def render_purpose_html(p, child):
    name = p.get('name').split('(')[0].split('.')[-1]
    ret=p.get('retention')
    if ret is None:
        ret = '(AfterPurpose, 0)'
    desc=p.get('description', '')
    if len(desc) == 0:
        desc = '""'
    
    data = render_purpose_data_html(p.get('data'))
    dr = render_purpose_dr_html(p.get('transfers'))
    
    mark = '\'' if child else ''

    return (
        '<table class="lane-item">'
        '  <tr><th colspan="2"><span>p{id}{mark}:</span><span> {name}</span></th></tr>'
        '  <tr><td class="key"><em>desc:</em></td><td>{desc}</td></tr>'
        '  <tr><td class="key"><em>optOut:</em></td><td>{optOut}</td></tr>'
        '  <tr><td class="key"><em>required:</em></td><td>{required}</td></tr>'
        '  <tr><td class="key"><em>retention:</em</td><td>{ret}</td></tr>'
        '  <tr><td class="key"><em>pm:</em></td><td>{pm}</td></tr>'
        '  <tr><td class="key"><em>D:</em></td><td>{data}</td></tr>'
        '  <tr><td class="key"><em>DR:</em></td><td>{dr}</td></tr>'
        + ('  <tr><td class="key"><em>p\':</em></td><td>{cp}</td></tr>' if not child else '')
        + '</table>'
    ).format(id=p.get('id'),name=name,optOut=p.get('optOut'),required=p.get('required'),
             ret=ret,pm=p.get('pm'),desc=desc, data=data, dr=dr, cp=len(p.get('purposes')),
             mark=mark)


def collect_transfers(purposes):
    uniq = []
    used = {}
    for p in purposes:
        for t in p.get('transfers'):
            hash = t.get('recipientId') + '_@_' + t.get('policyURL')
            if used.get(hash) is None:
                used[hash] = 1
                uniq.append(t)
    return sorted(uniq, key=lambda t: t['recipientId']) 


def render_transfer_html(t, i):
    return (
        '<table class="lane-item">'
        '  <tr><th colspan="2"><span>dr{id}:</span><span> {recipient}</span></th></tr>'
        '  <tr><td class="key"><em>policyURL:</em></td><td>{url}</td></tr>'
        '</table>'
    ).format(id=i+1,recipient=t.get('recipientId'),url=t.get('policyURL'))


def collect_data(purposes):
    all = []
    for p in purposes:
        all.extend(p.get('data'))
    result = list(set(all))
    result.sort()
    return result


def render_data_html(d, i):
    name = d.split('.')[-1]
    return (
        '<table class="lane-item">'
        '  <tr><th colspan="2"><span>d{id}:</span><span> {name}</span></th></tr>'
        '</table>'
    ).format(id=i+1,name=name)
    
    
def collect_composed_purposes(purposes, p_list):
    for p in purposes:
        p_list.extend(p.get('purposes'))


def render_composed_purposes_html(p):
    name = p.get('name').split('(')[0].split('.')[-1]
    
    template = '<div>{parent}<div>{children}</div></div>'
    
    top = (
        '<table class="lane-item composed-parent">'
        '  <tr><th colspan="2"><span>p{id}:</span><span> {name}</span></th></tr>'
        + '</table><div class="diamond"></div>'
    ).format(id=p.get('id'),name=name)
    
    cp = []
    for item in p.get('purposes'):
        cp_name = '#' + item.get('name').split('(')[0].split('.')[-1].split('#')[-1]
        mark = '\''
        cp.append(
            ('<div class="cp-container">'
             '<div><div class="cp-arrow-top"></div>'
             '<div class="cp-arrow-bottom"></div></div>'
             '<table class="composed-purpose lane-item">'
             '  <tr><th colspan="2"><span>p{id}{mark}:</span><span> {name}</span></th></tr>'
             '</table></div>').format(id=item.get('id'),name=cp_name,mark=mark))
    
    return template.format(parent=top, children=''.join(cp))
            

def render_results(folder, purpose_dict):
    with open(folder + 'report.html', "w") as f:
        folder_parts = folder.split('/')
        title = folder_parts[len(folder_parts) - 2]
                
        purposes_html = []
        for item in purpose_dict:
            purposes_html.append(render_purpose_html(item, False))
        
        child_purposes = []
        collect_composed_purposes(purpose_dict, child_purposes)
        for item in child_purposes:
            purposes_html.append(render_purpose_html(item, True))
        
        transfers = collect_transfers(purpose_dict)
        transfers_html = []
        for i,item in enumerate(transfers):
            transfers_html.append(render_transfer_html(item, i))
            
        data = collect_data(purpose_dict)
        data_html = []
        for i,item in enumerate(data):
            data_html.append(render_data_html(item, i))
            
        cp_html = []
        for item in purpose_dict:
            cp_html.append(render_composed_purposes_html(item))

        p_base = (
            '<section class="lane">'
                '<h2>Purposes</h2>'
                '{p}'
            '</section>'
        )
        p = ''.join(purposes_html)
        
        d_base = (
            '<section class="lane no-shrink">'
                '<h2>Data</h2>'
                '{d}'
            '</section>'
        )
        d = ''.join(data_html)
        
        dr_base = (
            '<section class="lane">'
                '<h2>DataRecipients</h2>'
                '{dr}'
            '</section>'
        )
        dr = ''.join(transfers_html)
        
        cp_base = (
            '<section>'
                '<h2>Composed Purposes</h2>'
                '{cp}'
            '</section>'
        )
        cp = ''.join(cp_html)
        
        f.write(HTML_BODY.format(title='Purpose data for ' + title,
                                 purposes=p_base.format(p=p),
                                 data=d_base.format(d=d),
                                 recipients=dr_base.format(dr=dr),
                                 composed=cp_base.format(cp=cp)))


def main():
    parser = argparse.ArgumentParser(description='Purpose Visualizer.', usage="python3 generate.py <folder> [<args>]")
    parser.add_argument("folder", type=str, help="Which folder to scan for Purpose files.");
    args = parser.parse_args(sys.argv[1:2])
    folder = args.folder
    if not folder.endswith('/'):
        folder = folder + '/'
    purpose_data = parse_purpose_files(folder)
    render_results(folder, purpose_data)    
    
if __name__ == '__main__':
    main()