"""Exercise the Android cache's production SQL with a real SQLite engine.

JVM Android stubs cannot execute SQLiteDatabase. Read the SQL from its source so
these integration checks complement the Java revision-domain unit tests.
"""
import pathlib
import re
import sqlite3
import unittest

SOURCE = (pathlib.Path(__file__).resolve().parents[1] /
          "android/app/src/main/java/com/kamyarps/tuneslink/LibraryCacheStore.java").read_text()


def literals(expression):
    return "".join(re.findall(r'"([^"\\]*)"', expression))


class CacheRevisionTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        schema = re.search(r'db.execSQL\(("CREATE TABLE pages .*?)\);', SOURCE, re.S)
        self.assertIsNotNone(schema)
        self.db.execute(literals(schema.group(1)))
        selection = re.search(r'STALE_REVISION_SELECTION\s*=\s*(.*?);', SOURCE, re.S)
        # The legacy scope-wide query must fail the same regression, before the fix.
        if selection:
            self.selection = literals(selection.group(1))
        else:
            self.selection = re.search(r'db.delete\("pages", "(scope = \? AND revision [^"]+)"', SOURCE)[1]

    def save(self, scope, key, domain, revision):
        if revision:
            args = (scope, domain, revision) if "revision_domain" in self.selection else (scope, revision)
            self.db.execute("DELETE FROM pages WHERE " + self.selection, args)
        columns = [row[1] for row in self.db.execute("PRAGMA table_info(pages)")]
        values = dict(scope=scope, request_key=key, kind=1, revision=revision,
                      payload="{}", stored_at=1, accessed_at=1, revision_domain=domain)
        self.db.execute("INSERT OR REPLACE INTO pages (" + ",".join(columns) + ") VALUES (" +
                        ",".join("?" for _ in columns) + ")", [values[c] for c in columns])

    def keys(self):
        return {row[0] for row in self.db.execute("SELECT request_key FROM pages")}

    def test_unrelated_revision_producers_do_not_evict_each_other(self):
        for key, domain, revision in [("songs", "library", "snapshot-1"),
                                      ("albums", "library", "snapshot-1"),
                                      ("lists", "playlist-list:", "lists-1"),
                                      ("filtered", "playlist-list:rock", "filtered-1"),
                                      ("p1", "playlist:p1", "p1-1"),
                                      ("p2", "playlist:p2", "p2-1")]:
            self.save("pc", key, domain, revision)
        self.assertEqual({"songs", "albums", "lists", "filtered", "p1", "p2"}, self.keys())
        self.save("pc", "p1-new", "playlist:p1", "p1-2")
        self.assertEqual({"songs", "albums", "lists", "filtered", "p1-new", "p2"}, self.keys())

    def test_new_library_snapshot_evicts_only_its_own_old_pages(self):
        self.save("pc", "songs", "library", "old")
        self.save("pc", "albums", "library", "old")
        self.save("pc", "unknown-revision", "library", "")
        self.save("other-pc", "other", "library", "old")
        self.save("pc", "lists", "playlist-list:", "different")
        self.save("pc", "new", "library", "new")
        self.assertEqual({"unknown-revision", "other", "lists", "new"}, self.keys())

    def test_upgrade_recreates_optional_cache_with_domain_column(self):
        # Start with a populated version-1 database, then execute the actual upgrade SQL.
        schema = re.search(r'db.execSQL\(("CREATE TABLE pages .*?)\);', SOURCE, re.S)[1]
        self.db.execute("DROP TABLE pages")
        self.db.execute(literals(schema).replace("revision_domain TEXT NOT NULL, ", ""))
        self.db.execute("INSERT INTO pages VALUES (?,?,?,?,?,?,?)",
                        ("pc", "old-cache", 1, "old", "{}", 1, 1))
        upgrade = re.search(r'void onUpgrade\(.*?\{(.*?)\n        \}', SOURCE, re.S)[1]
        self.db.execute(literals(re.search(r'db.execSQL\((.*?)\);', upgrade, re.S)[1]))
        self.assertIn("onCreate(db)", upgrade)
        self.db.execute(literals(schema))
        self.assertIn("revision_domain", [row[1] for row in self.db.execute("PRAGMA table_info(pages)")])
        self.save("pc", "fresh", "library", "new")
        self.assertEqual({"fresh"}, self.keys())


if __name__ == "__main__":
    unittest.main()
