"""Verify the service gate and overall-code academic limits; never infer success."""
import argparse
import json
import math
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlencode
from urllib.request import Request, urlopen

HOST = 'https://sonarcloud.io'
METRICS = ('ncloc', 'bugs', 'vulnerabilities', 'duplicated_lines_density',
           'reliability_rating', 'security_rating', 'security_hotspots',
           'security_hotspots_reviewed', 'line_coverage')


class EvidenceError(ValueError):
    """A controlled diagnostic that is safe to include in QA artifacts."""


def analysis_mode(event, ref, base, main_branch):
    """Free analyzes the main branch and PRs targeting it, never dev/releases."""
    if main_branch not in ('main', 'master'):
        raise EvidenceError('Unexpected repository main branch')
    if event == 'pull_request':
        return 'pull_request' if base == main_branch else 'unsupported'
    if event in ('push', 'workflow_dispatch'):
        if ref == 'refs/heads/' + main_branch:
            return 'branch'
        if ref.startswith('refs/tags/v'):
            return 'existing'
    return 'unsupported'


def api(path, **query):
    request = Request(HOST + '/api/' + path + '?' + urlencode(query),
                      headers={'Authorization': 'Bearer ' + os.environ['SONAR_TOKEN']})
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def overall_errors(values):
    errors = []
    for key in METRICS:
        if key == 'security_hotspots_reviewed' and values.get('security_hotspots') == 0:
            continue  # No hotspots exist to review. Other absent metrics always fail.
        if key not in values or not math.isfinite(values[key]):
            errors.append('Missing/invalid overall metric: ' + key)
    if errors:
        return errors
    limits = {'bugs': (0, 'max'), 'vulnerabilities': (0, 'max'),
              'duplicated_lines_density': (3, 'max'), 'reliability_rating': (1, 'max'),
              'security_rating': (1, 'max'), 'line_coverage': (85, 'min'), 'ncloc': (1, 'min')}
    if values['security_hotspots'] > 0:
        limits['security_hotspots_reviewed'] = (100, 'min')
    for key, (limit, direction) in limits.items():
        if (direction == 'max' and values[key] > limit) or (direction == 'min' and values[key] < limit):
            errors.append(f'{key}={values[key]} violates {direction} {limit}')
    return errors


def verify(task_file, project, branch, revision, pull_request=''):
    task = dict(line.split('=', 1) for line in Path(task_file).read_text().splitlines() if '=' in line)
    if task.get('serverUrl', '').rstrip('/') != HOST or task.get('projectKey') != project:
        raise EvidenceError('Scanner task does not match the configured server/project')
    result = api('ce/task', id=task['ceTaskId'])['task']
    if result['status'] != 'SUCCESS' or result.get('componentKey') != project:
        raise EvidenceError('Analysis task did not succeed for this project')
    analysis = result['analysisId']
    return verify_analysis(project, branch, revision, analysis, pull_request)


def verify_existing(project, branch, revision):
    latest = api('project_analyses/search', project=project, branch=branch, ps=1)['analyses']
    if not latest or latest[0].get('revision') != revision:
        raise EvidenceError('No latest main-branch analysis for this exact SHA; analyze master/main first. Free cannot analyze dev or release branches')
    report = verify_analysis(project, branch, revision, latest[0]['key'])
    report['evidenceMode'] = 'existing-main-analysis'
    return report


def verify_analysis(project, branch, revision, analysis, pull_request=''):
    status = api('qualitygates/project_status', analysisId=analysis)['projectStatus']
    errors = [] if status['status'] == 'OK' else ['SonarCloud Quality Gate is not OK']
    report = {'project': project, 'revision': revision, 'analysisId': analysis,
              'qualityGate': status['status'], 'scope': 'pull-request-new-code' if pull_request else 'overall-code'}
    if pull_request:
        # PR APIs only provide new-code measures. A PR is never release evidence.
        report['pullRequest'] = pull_request
        report['overallVerified'] = False
    else:
        branches = api('project_branches/list', project=project)['branches']
        selected = next((item for item in branches if item['name'] == branch), None)
        if not selected or selected.get('type') != 'LONG' or not selected.get('isMain'):
            raise EvidenceError('Free overall-code evidence must belong to the actual Sonar main branch')
        report['branchType'] = selected['type']
        def check_revision():
            latest = api('project_analyses/search', project=project, branch=branch, ps=1)['analyses']
            if not latest or latest[0]['key'] != analysis or latest[0].get('revision') != revision:
                raise EvidenceError('Latest main-branch analysis does not match this task/revision; retry after main analysis completes')
        check_revision()
        measures = api('measures/component', component=project, branch=branch,
                       metricKeys=','.join(METRICS))['component']['measures']
        values = {item['metric']: float(item['value']) for item in measures if 'value' in item}
        errors.extend(overall_errors(values))
        check_revision()  # Fail on a concurrent analysis replacing the measured revision.
        report.update(branch=branch, metrics=values, overallVerified=not errors)
    report['errors'] = errors
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--scope', action='store_true')
    parser.add_argument('--main-branch', choices=('main', 'master'))
    parser.add_argument('--task')
    parser.add_argument('--existing-analysis', action='store_true')
    parser.add_argument('--output')
    args = parser.parse_args()
    if args.scope:
        if not args.main_branch:
            parser.error('--scope requires --main-branch')
        mode = analysis_mode(os.environ.get('GITHUB_EVENT_NAME', ''), os.environ.get('GITHUB_REF', ''),
                             os.environ.get('GITHUB_BASE_REF', ''), args.main_branch)
        message = {'branch': 'Analyze main branch and verify overall metrics.',
                   'pull_request': 'Analyze PR to main; new-code gate only, no release accreditation.',
                   'existing': 'Validate tag using an existing main analysis of this exact SHA; otherwise fail release validation.',
                   'unsupported': 'NOT ANALYZED: validation-only scope under Free. Tests, coverage and SCA still run; no Sonar approval is claimed. Publication and deployment are blocked. Use a PR to the main branch for real Sonar analysis.'}[mode]
        print('SonarCloud Free: ' + message)
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
                output.write(f'sonar_mode={mode}\nsonar_branch={args.main_branch}\n')
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
                summary.write('### SonarCloud Free\n\n' + message + '\n')
        return 0
    if not args.output or (bool(args.task) == args.existing_analysis):
        parser.error('Use --output and exactly one of --task or --existing-analysis')
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {'qualityGate': 'NOT_VERIFIED', 'overallVerified': False}
    try:
        for name in ('SONAR_TOKEN', 'SONAR_PROJECT_KEY', 'SONAR_ORGANIZATION', 'GITHUB_SHA'):
            if not os.environ.get(name, '').strip():
                raise EvidenceError('Missing required configuration: ' + name)
        if args.existing_analysis:
            branch = os.environ.get('SONAR_BRANCH', '')
            if branch not in ('main', 'master'):
                raise EvidenceError('Existing evidence requires the configured repository main branch')
            ancestry = subprocess.run(['git', 'merge-base', '--is-ancestor', os.environ['GITHUB_SHA'],
                                       'refs/remotes/origin/' + branch], capture_output=True, timeout=15)
            if ancestry.returncode:
                raise EvidenceError('This SHA is not reachable from the repository main branch; no release/publication evidence')
            report = verify_existing(os.environ['SONAR_PROJECT_KEY'], branch, os.environ['GITHUB_SHA'])
        else:
            report = verify(args.task, os.environ['SONAR_PROJECT_KEY'], os.environ.get('SONAR_BRANCH', ''),
                            os.environ['GITHUB_SHA'], os.environ.get('SONAR_PULL_REQUEST', ''))
    except EvidenceError as error:
        report['errors'] = [str(error)]
    except Exception:
        # URLs/HTTP error bodies may contain sensitive data. Do not log them.
        report['errors'] = ['Sonar evidence unavailable or invalid; check service, permissions, configuration and task/revision']
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('Sonar verification: ' + ('FAIL' if report.get('errors') else 'PASS (' + report['scope'] + ')'))
    return 1 if report.get('errors') else 0


if __name__ == '__main__':
    sys.exit(main())
