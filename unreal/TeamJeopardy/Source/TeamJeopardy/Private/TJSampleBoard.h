#pragma once

#include "TJTypes.h"

/** Reads Content/Boards/sample-board.json. Same file the browser preview uses. */
bool TJLoadSampleBoard(FTJBoard& OutBoard, FString& OutError);
