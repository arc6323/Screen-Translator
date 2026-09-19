"""Compile the pinned OPUS tokenizer into an indexed UTF-16 trie; no runtime JSON parsing."""
import json
import pathlib
import struct
import sys

source, destination = map(pathlib.Path, sys.argv[1:])
data = json.loads(source.read_text())
assert data['model']['type'] == 'Unigram' and data['model']['unk_id'] == 2
# The conversion's unk_id points to a comma. Use its explicit <unk> special token (1).
assert data['normalizer'] == {'type': 'Precompiled', 'precompiled_charsmap': None}
vocab = data['model']['vocab']
assert len(vocab) == 62518
assert [vocab[i][0] for i in (0, 1, 62517)] == ['</s>', '<unk>', '<pad>']
nodes = [{'id': -1, 'score': 0.0, 'children': {}}]
for token, (piece, score) in enumerate(vocab):
    if token in (0, 1, 62517):
        continue
    node = 0
    units = piece.encode('utf-16-be')
    for (unit,) in struct.iter_unpack('>H', units):
        children = nodes[node]['children']
        if unit not in children:
            children[unit] = len(nodes)
            nodes.append({'id': -1, 'score': 0.0, 'children': {}})
        node = children[unit]
    nodes[node]['id'] = token
    nodes[node]['score'] = score

with destination.open('wb') as out:
    def write(fmt, *values): out.write(struct.pack('>' + fmt, *values))
    write('IId', 0x554E4931, len(vocab), min(score for _, score in vocab) - 10)
    for piece, _ in vocab:
        encoded = piece.encode('utf-8')
        write('I', len(encoded)); out.write(encoded)
    write('I', len(nodes))
    edges = []
    for node in nodes:
        children = sorted(node['children'].items())
        write('ifII', node['id'], node['score'], len(edges), len(children))
        edges.extend(children)
    write('I', len(edges))
    for char, target in edges: write('HI', char, target)
print(f'Compiled {len(vocab)} tokens, {len(nodes)} trie nodes, {destination.stat().st_size} bytes')
