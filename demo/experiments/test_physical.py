"""Чистые проверки построения DDL; не обращаются к Docker/PostgreSQL."""
import copy
import unittest

import physical


class PhysicalDdlTest(unittest.TestCase):
    def candidate(self):
        table = {"schema": "public", "name": "records"}
        return {"id": "stable-id", "ddl": "DROP DATABASE unrelated;",
                "definition": {"table": table, "method": "btree", "unique": False,
                               "keys": [{"column": {"table": table, "name": 'strange"column'},
                                         "direction": "desc", "nulls": "last",
                                         "collation": None, "operator_class": "pg_catalog.int4_ops"}]}}

    def test_rebuilds_quoted_canonical_definition_and_ignores_free_text_ddl(self):
        name, ddl = physical.physical_ddl(self.candidate())
        self.assertIn('"strange""column" "pg_catalog"."int4_ops" DESC NULLS LAST', ddl)
        self.assertIn('ON "public"."records"', ddl)
        self.assertNotIn("DROP", ddl)
        self.assertEqual(name, physical.physical_ddl(self.candidate())[0])

    def test_rejects_nonseed_table_and_unsupported_key_directions(self):
        candidate = self.candidate()
        candidate["definition"]["table"]["schema"] = "production"
        with self.assertRaises(ValueError):
            physical.physical_ddl(candidate)
        candidate = self.candidate()
        candidate["definition"]["keys"][0]["direction"] = "asc; DELETE FROM public.records"
        with self.assertRaises(ValueError):
            physical.physical_ddl(candidate)

    def test_does_not_accept_keys_from_another_table(self):
        candidate = copy.deepcopy(self.candidate())
        candidate["definition"]["keys"][0]["column"]["table"] = {"schema": "public", "name": "groups"}
        with self.assertRaises(ValueError):
            physical.physical_ddl(candidate)

    def test_qualified_names_accept_canonical_quotes_and_embedded_dots(self):
        self.assertEqual('"pg_catalog"."int4_ops"', physical.qualified('"pg_catalog"."int4_ops"'))
        self.assertEqual('"schema.with.dot"."name""quoted"', physical.qualified('"schema.with.dot"."name""quoted"'))
        self.assertEqual('"pg_catalog"."int4_ops"', physical.qualified('PG_CATALOG.INT4_OPS'))
        for malformed in ['public.ops; DELETE FROM public.records', '"public.ops"', 'a.b.c', '"a"."b']:
            with self.subTest(malformed=malformed), self.assertRaises(ValueError):
                physical.qualified(malformed)

    def test_canonical_quoted_operator_class_is_not_double_quoted(self):
        candidate = self.candidate()
        candidate["definition"]["keys"][0]["operator_class"] = '"pg_catalog"."int4_ops"'
        self.assertIn('"pg_catalog"."int4_ops" DESC', physical.physical_ddl(candidate)[1])


if __name__ == "__main__":
    unittest.main()
