"""Apply individual analysis to completed, sanitized evidence without changing detections."""
import html
import json
import pathlib
import sys
from scan import evaluate


def write(path, content):
    temp = path.with_suffix(path.suffix + '.tmp')
    temp.write_text(content)
    temp.replace(path)


def review(directory):
    directory = pathlib.Path(directory)
    report = json.loads((directory / 'report.json').read_text())
    execution = json.loads((directory / 'execution.json').read_text())
    if report.get('complete') is not True or execution.get('complete') is not True:
        raise ValueError('Cannot accept incomplete or failed scanner execution')
    treatments = json.loads(pathlib.Path(__file__).with_name('treatments.json').read_text())
    pending = evaluate(report['alerts'], treatments)
    report['pending_findings'] = pending
    report['status'] = 'PENDING' if pending else 'PASS'
    report['review_note'] = 'Individual treatment applied after scan; detections, severities and authentication evidence preserved.'
    write(directory / 'report.json', json.dumps(report, ensure_ascii=False, indent=2))
    write(directory / 'report.html', '<!doctype html><html lang="es"><meta charset="utf-8"><title>SEG-07</title><h1>SEG-07</h1><pre>' + html.escape(json.dumps(report, ensure_ascii=False, indent=2)) + '</pre></html>')
    execution.update({k: report[k] for k in ['status', 'pending_findings', 'review_note']})
    write(directory / 'execution.json', json.dumps(execution, ensure_ascii=False, indent=2))
    return not pending


if __name__ == '__main__':
    sys.exit(0 if review(sys.argv[1]) else 1)
