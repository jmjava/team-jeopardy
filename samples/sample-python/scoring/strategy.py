from abc import ABC, abstractmethod


class ScoringStrategy(ABC):
    @abstractmethod
    def score(self, value: int) -> int:
        raise NotImplementedError


class DoubleScoringStrategy(ScoringStrategy):
    def score(self, value: int) -> int:
        return value * 2


class FlatScoringStrategy(ScoringStrategy):
    def score(self, value: int) -> int:
        return value
