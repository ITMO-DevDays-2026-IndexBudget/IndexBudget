import copy
import unittest
from test_server import request, TEMPLATES
from server import validate_request


def observed():
    data = request()
    data['write_source'] = 'observed'
    data['workload']['table_write_rates'] = {'public.records': {
        'inserts_per_hour': .000125, 'updates_per_hour': 2000000.5, 'deletes_per_hour': 0, 'source': 'observed'}}
    data['workload']['table_write_rates_origin'] = {
        'kind': 'observed', 'window': {'from': '2026-11-12T10:00:00Z', 'to': '2026-11-12T12:00:00Z', 'period_seconds': 7200},
        'complete': True, 'reasons': [], 'source': {'source_id': '11111111-1111-1111-1111-111111111111',
                                                   'database_oid': 16384, 'postgres_version': '16.15'}}
    return data


class WriteProfileTests(unittest.TestCase):
    def test_observed_exact_rates_and_window_pass_single_and_series(self):
        for series in (None, {'schema_version': 1, 'budget': 'write_budget_units_per_hour', 'values': [0, .125, 9000000]}):
            data = observed()
            if series: data['series'] = series
            result = validate_request(data, TEMPLATES)
            self.assertEqual(data['workload'], result['workload'])
            self.assertEqual(result['write_source'], 'observed')

    def test_incomplete_never_disables_budget_or_falls_back(self):
        data = observed()
        data['workload']['table_write_rates_origin'].update(complete=False, reasons=['INCOMPLETE_SNAPSHOT'])
        for limit in (0, 100):
            data['workload']['constraints']['write_budget_units_per_hour'] = limit
            with self.assertRaisesRegex(ValueError, 'Наблюдение неполное'): validate_request(data, TEMPLATES)
        data['workload']['constraints']['write_budget_units_per_hour'] = None
        self.assertEqual(data['workload'], validate_request(data, TEMPLATES)['workload'])
        data['series'] = {'schema_version': 1, 'budget': 'write_budget_units_per_hour', 'values': [0, 1, 2]}
        with self.assertRaisesRegex(ValueError, 'Наблюдение неполное'): validate_request(data, TEMPLATES)

    def test_missing_unknown_and_mixed_sources_are_errors(self):
        for mutation in ('missing', 'unknown', 'mixed', 'no_origin', 'manual_choice'):
            data = observed()
            if mutation == 'missing': del data['workload']['table_write_rates']
            if mutation == 'unknown': data['workload']['table_write_rates']['public.records']['updates_per_hour'] = None
            if mutation == 'mixed': data['workload']['table_write_rates']['public.records']['source'] = 'manual'
            if mutation == 'no_origin': del data['workload']['table_write_rates_origin']
            if mutation == 'manual_choice': data['write_source'] = 'manual'
            with self.assertRaises(ValueError, msg=mutation): validate_request(data, TEMPLATES)

    def test_absent_profile_is_not_zero_and_disabled_limit_stays_disabled(self):
        data = request()
        del data['workload']['table_write_rates']
        data['workload']['constraints']['write_budget_units_per_hour'] = None
        self.assertEqual(data['workload'], validate_request(data, TEMPLATES)['workload'])
        data['workload']['constraints']['write_budget_units_per_hour'] = 0
        with self.assertRaisesRegex(ValueError, 'не задан'): validate_request(data, TEMPLATES)
