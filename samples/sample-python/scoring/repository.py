from dataclasses import dataclass


@dataclass
class ClueRecord:
    category: str
    value: int


class ClueRepository:
    def __init__(self) -> None:
        self._items: list[ClueRecord] = []

    def add(self, record: ClueRecord) -> None:
        self._items.append(record)

    def all(self) -> list[ClueRecord]:
        return list(self._items)


def create_repository() -> ClueRepository:
    return ClueRepository()
