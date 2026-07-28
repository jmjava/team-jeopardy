from contextlib import contextmanager
from functools import wraps
from typing import Callable, Iterator


def timed(fn: Callable):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        return fn(*args, **kwargs)

    return wrapper


@contextmanager
def score_session() -> Iterator[dict]:
    state = {"active": True}
    try:
        yield state
    finally:
        state["active"] = False


class ConfigSingleton:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance.room = ""
        return cls._instance
