#include "TJSampleBoard.h"

#include "Dom/JsonObject.h"
#include "Misc/FileHelper.h"
#include "Misc/Paths.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"

namespace
{
	FString Field(const TSharedPtr<FJsonObject>& Object, const TCHAR* Name)
	{
		FString Value;
		if (Object.IsValid())
		{
			Object->TryGetStringField(Name, Value);
		}
		return Value;
	}
}

bool TJLoadSampleBoard(FTJBoard& OutBoard, FString& OutError)
{
	const FString Path = FPaths::ProjectContentDir() / TEXT("Boards/sample-board.json");
	FString Raw;
	if (!FFileHelper::LoadFileToString(Raw, *Path))
	{
		OutError = FString::Printf(TEXT("Could not read %s"), *Path);
		return false;
	}

	TSharedPtr<FJsonObject> Root;
	const TSharedRef<TJsonReader<>> Reader = TJsonReaderFactory<>::Create(Raw);
	if (!FJsonSerializer::Deserialize(Reader, Root) || !Root.IsValid())
	{
		OutError = FString::Printf(TEXT("%s is not valid JSON"), *Path);
		return false;
	}

	OutBoard = FTJBoard();
	OutBoard.Title = Field(Root, TEXT("title"));
	if (OutBoard.Title.IsEmpty())
	{
		OutBoard.Title = TEXT("Team Jeopardy");
	}

	const TArray<TSharedPtr<FJsonValue>>* Categories = nullptr;
	if (!Root->TryGetArrayField(TEXT("categories"), Categories) || Categories == nullptr)
	{
		OutError = TEXT("sample-board.json has no categories");
		return false;
	}

	for (const TSharedPtr<FJsonValue>& CategoryValue : *Categories)
	{
		const TSharedPtr<FJsonObject> CategoryObject = CategoryValue.IsValid() ? CategoryValue->AsObject() : nullptr;
		if (!CategoryObject.IsValid())
		{
			continue;
		}

		FTJCategory Category;
		Category.Id = Field(CategoryObject, TEXT("id"));
		Category.Title = Field(CategoryObject, TEXT("title"));

		const TArray<TSharedPtr<FJsonValue>>* Clues = nullptr;
		if (CategoryObject->TryGetArrayField(TEXT("clues"), Clues) && Clues != nullptr)
		{
			for (const TSharedPtr<FJsonValue>& ClueValue : *Clues)
			{
				const TSharedPtr<FJsonObject> ClueObject = ClueValue.IsValid() ? ClueValue->AsObject() : nullptr;
				if (!ClueObject.IsValid())
				{
					continue;
				}

				FTJClue Clue;
				Clue.Id = Field(ClueObject, TEXT("id"));
				if (Clue.Id.IsEmpty())
				{
					continue;
				}
				double ValueNumber = 0.0;
				if (ClueObject->TryGetNumberField(TEXT("value"), ValueNumber))
				{
					Clue.Value = FMath::RoundToInt(ValueNumber);
				}
				Clue.Prompt = Field(ClueObject, TEXT("prompt"));
				Clue.Response = Field(ClueObject, TEXT("response"));
				Clue.Explanation = Field(ClueObject, TEXT("explanation"));
				Clue.SourcePath = Field(ClueObject, TEXT("sourcePath"));
				bool bDailyDouble = false;
				if (ClueObject->TryGetBoolField(TEXT("dailyDouble"), bDailyDouble))
				{
					Clue.bDailyDouble = bDailyDouble;
				}
				Category.Clues.Add(Clue);
			}
		}

		if (Category.Clues.Num() > 0)
		{
			OutBoard.Categories.Add(Category);
		}
	}

	if (OutBoard.Categories.Num() == 0)
	{
		OutError = TEXT("sample-board.json did not contain any clues");
		return false;
	}
	return true;
}
