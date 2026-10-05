#!/usr/bin/env python3
"""Summarize a schema-2 session and validate its refresh receipts and append sequence."""
import argparse
import hashlib
import json
from pathlib import Path


def audit(path):
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    start = next(row for row in rows if row['event'] == 'start')
    complete = next((row for row in rows if row['event'] == 'complete'), None)
    offers = [row['kind'] for row in start['offers']]
    assert 'IRON_INGOT' not in offers
    close = None; receipts = []; results = []; initialization = []
    for row in rows:
        event = row['event']
        if event == 'log_dropped':
            raise AssertionError('Session lost log records')
        if event == 'close_for_refresh':
            close = row; receipts = []
        if event == 'close_receipt':
            receipts.append(row['tick'])
        if event == 'worker' and row['operation'] == 'initialize':
            initialization.append(dict(wallSeconds=row['elapsedNanos'] / 1e9,
                                       threadCpuSeconds=row['cpuNanos'] / 1e9,
                                       candidates=row['states'], pending=row['pending']))
        if event == 'refresh':
            bounds = row['closeTicks']; admitted = close['admittedTicks']
            assert receipts == [bounds['first'], bounds['last']]
            assert admitted['first'] <= bounds['first'] <= bounds['last'] <= admitted['last']
            assert row['before'] == len(offers) and row['after'] == len(offers) + 1
            assert row['appended']['kind'] not in offers
            if row['appended']['kind'] == 'IRON_INGOT':
                assert len(offers) == 25
            offers.append(row['appended']['kind'])
            results.append(dict(number=row['number'], kind=row['appended']['kind'], price=row['appended']['price'],
                                admittedTicks=admitted, closeTicks=bounds, openedTick=row['openedTick'],
                                closedTicks=[row['openedTick']-bounds['last'], row['openedTick']-bounds['first']],
                                observedWallTicks=(row['openedTime']-row['closeTime']) / 50_000_000))
    if complete:
        assert len(results) == 25 and len(set(offers)) == 26 and offers[-1] == 'IRON_INGOT'
        assert [offer['kind'] for offer in complete['offers']] == offers
    clock_changes = [dict(tick=row['tick'], entity=row['clock']['entity'], age=row['clock']['age'])
                     for row in rows if row['event'] == 'clock_selected' and row.get('clock')]
    return dict(logSha256=hashlib.sha256(path.read_bytes()).hexdigest(), complete=complete is not None,
                elapsedSeconds=((complete or rows[-1])['nanoTime']-start['nanoTime'])/1e9,
                errors=[r['reason'] for r in rows if r['event']=='error'],
                droppedRecords=0, initialization=initialization, clockChanges=clock_changes,
                discardedPlans=sum(r['event']=='plan_discarded' for r in rows), refreshes=results)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('session', type=Path)
    args = parser.parse_args()
    print(json.dumps(audit(args.session), indent=2))
