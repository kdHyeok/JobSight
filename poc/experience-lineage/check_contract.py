"""Design 6.8 reference model and synthetic contract checks; not server code."""

import unittest


def lineage(experience_id, owner, experiences):
    chain = []
    while experience_id is not None:
        if experience_id in chain:
            raise ValueError("MERGE_CYCLE")
        experience = experiences.get(experience_id)
        if experience is None or experience["owner"] != owner:
            raise LookupError("EXPERIENCE_NOT_FOUND")
        chain.append(experience_id)
        experience_id = experience["merged_into"]
    return chain


def needs_review(based_on, owner, experiences, revisions):
    chain = lineage(based_on[0], owner, experiences)
    base_time = revisions[based_on]["created_at"]
    return any(
        experience_id in chain and item["kind"] == "CORRECT"
        and item["created_at"] > base_time
        for (experience_id, _), item in revisions.items()
    )


def validate_reference(ref, owner, experiences, revisions, episodes):
    if (not isinstance(ref, dict)
            or not all(isinstance(ref.get(key), str) and ref[key] for key in ("id", "experienceId"))
            or type(ref.get("experienceRevision")) is not int or ref["experienceRevision"] < 1):
        raise ValueError("EPISODE_REFERENCE_REQUIRED")
    episode = episodes.get(ref.get("id"))
    if episode is None or episode["owner"] != owner:
        raise LookupError("EPISODE_NOT_FOUND")
    experience_id = ref["experienceId"]
    source_chain = lineage(experience_id, owner, experiences)
    pair = experience_id, ref["experienceRevision"]
    if pair not in revisions:
        raise LookupError("EXPERIENCE_REVISION_NOT_FOUND")
    if source_chain[-1] != lineage(episode["experience_id"], owner, experiences)[-1]:
        raise ValueError("EPISODE_EXPERIENCE_MISMATCH")
    return pair


class ContractChecks(unittest.TestCase):
    def setUp(self):
        self.experiences = {
            "a": {"owner": "u", "merged_into": "b"},
            "b": {"owner": "u", "merged_into": "c"},
            "c": {"owner": "u", "merged_into": None},
            "d": {"owner": "u", "merged_into": None},
            "foreign": {"owner": "v", "merged_into": None},
        }
        self.revisions = {
            ("a", 1): {"kind": "INIT", "created_at": 1},
            ("a", 2): {"kind": "CORRECT", "created_at": 3},
            ("a", 4): {"kind": "ADD", "created_at": 4},
            ("b", 1): {"kind": "INIT", "created_at": 2},
            ("b", 2): {"kind": "MERGE", "created_at": 5},
            ("c", 1): {"kind": "INIT", "created_at": 0},
            ("c", 4): {"kind": "MERGE", "created_at": 6},
            ("d", 1): {"kind": "INIT", "created_at": 1},
        }
        self.episodes = {
            "e": {"owner": "u", "experience_id": "c"},
            "other": {"owner": "u", "experience_id": "d"},
            "foreign": {"owner": "v", "experience_id": "foreign"},
        }

    def review(self, pair):
        return needs_review(pair, "u", self.experiences, self.revisions)

    def reference(self, **values):
        ref = {"id": "e", "experienceId": "a", "experienceRevision": 4}
        return validate_reference(ref | values, "u", self.experiences, self.revisions, self.episodes)

    def test_source_correction_survives_merge(self):
        self.assertTrue(self.review(("a", 1)))

    def test_intermediate_correction_survives_second_merge(self):
        self.revisions[("a", 2)]["kind"] = "ADD"
        self.revisions[("b", 2)]["kind"] = "CORRECT"
        self.assertTrue(self.review(("a", 1)))

    def test_target_correction_after_reference(self):
        self.revisions[("c", 4)]["kind"] = "CORRECT"
        self.assertTrue(self.review(("a", 4)))

    def test_add_and_merge_alone_do_not_warn(self):
        self.revisions[("a", 2)]["kind"] = "ADD"
        self.assertFalse(self.review(("a", 1)))

    def test_correction_older_than_reference_does_not_warn(self):
        self.assertFalse(self.review(("a", 4)))

    def test_unrelated_correction_does_not_warn(self):
        self.revisions[("d", 2)] = {"kind": "CORRECT", "created_at": 7}
        self.assertFalse(self.review(("a", 4)))

    def test_explicit_source_id_round_trip_with_same_revision_number(self):
        self.assertEqual(self.reference(), ("a", 4))
        self.assertEqual(self.reference(experienceId="c"), ("c", 4))

    def test_missing_experience_id_is_rejected(self):
        with self.assertRaises(ValueError):
            validate_reference({"id": "e", "experienceRevision": 4}, "u",
                               self.experiences, self.revisions, self.episodes)

    def test_missing_or_invalid_revision_is_rejected(self):
        for value in (0, -1, True, 1.5, None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.reference(experienceRevision=value)
        with self.assertRaises(LookupError):
            self.reference(experienceRevision=99)

    def test_foreign_owner_is_rejected(self):
        with self.assertRaises(LookupError):
            self.reference(experienceId="foreign")
        with self.assertRaises(LookupError):
            self.reference(id="foreign")

    def test_unrelated_episode_is_rejected(self):
        with self.assertRaises(ValueError):
            self.reference(id="other", experienceRevision=1)

    def test_cycle_and_foreign_merge_path_are_rejected(self):
        self.experiences["c"]["merged_into"] = "a"
        with self.assertRaisesRegex(ValueError, "MERGE_CYCLE"):
            self.review(("a", 1))
        self.experiences["c"]["merged_into"] = "foreign"
        with self.assertRaises(LookupError):
            self.review(("a", 1))


if __name__ == "__main__":
    unittest.main()
