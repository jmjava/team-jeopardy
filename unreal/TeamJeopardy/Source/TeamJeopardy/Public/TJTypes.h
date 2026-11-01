#pragma once

#include "CoreMinimal.h"
#include "TJTypes.generated.h"

/** Same beats as the Java GamePhase enum. Final is unused, matching main. */
UENUM(BlueprintType)
enum class ETJPhase : uint8
{
	Lobby,
	Board,
	HostPreview,
	ClueOpen,
	BuzzLocked,
	AnswerRevealed,
	Final,
	Finished
};

UENUM(BlueprintType)
enum class ETJFaceKind : uint8
{
	Category,
	Value,
	Answered
};

USTRUCT(BlueprintType)
struct FTJClue
{
	GENERATED_BODY()

	UPROPERTY(EditAnywhere, BlueprintReadOnly)
	FString Id;

	UPROPERTY(EditAnywhere, BlueprintReadOnly)
	int32 Value = 0;

	UPROPERTY(EditAnywhere, BlueprintReadOnly)
	FString Prompt;

	UPROPERTY(EditAnywhere, BlueprintReadOnly)
	FString Response;

	UPROPERTY(EditAnywhere, BlueprintReadOnly)
	FString Explanation;

	UPROPERTY(EditAnywhere, BlueprintReadOnly)
	FString SourcePath;

	UPROPERTY(EditAnywhere, BlueprintReadOnly)
	bool bDailyDouble = false;
};

USTRUCT(BlueprintType)
struct FTJCategory
{
	GENERATED_BODY()

	UPROPERTY(EditAnywhere, BlueprintReadOnly)
	FString Id;

	UPROPERTY(EditAnywhere, BlueprintReadOnly)
	FString Title;

	UPROPERTY(EditAnywhere, BlueprintReadOnly)
	TArray<FTJClue> Clues;
};

USTRUCT(BlueprintType)
struct FTJBoard
{
	GENERATED_BODY()

	UPROPERTY(EditAnywhere, BlueprintReadOnly)
	FString Title;

	UPROPERTY(EditAnywhere, BlueprintReadOnly)
	TArray<FTJCategory> Categories;
};

USTRUCT(BlueprintType)
struct FTJTeam
{
	GENERATED_BODY()

	UPROPERTY(EditAnywhere, BlueprintReadOnly)
	FString Id;

	UPROPERTY(EditAnywhere, BlueprintReadOnly)
	FString Name;

	UPROPERTY(EditAnywhere, BlueprintReadOnly)
	int32 Score = 0;

	UPROPERTY(EditAnywhere, BlueprintReadOnly)
	FLinearColor Color = FLinearColor::White;
};

/** What the clue wall, podiums, and operator hint read. */
USTRUCT(BlueprintType)
struct FTJMatchView
{
	GENERATED_BODY()

	UPROPERTY(BlueprintReadOnly)
	ETJPhase Phase = ETJPhase::Lobby;

	UPROPERTY(BlueprintReadOnly)
	FString BoardTitle;

	UPROPERTY(BlueprintReadOnly)
	TArray<FTJTeam> Teams;

	UPROPERTY(BlueprintReadOnly)
	TArray<FString> AnsweredClueIds;

	UPROPERTY(BlueprintReadOnly)
	FString CategoryTitle;

	UPROPERTY(BlueprintReadOnly)
	int32 ClueValue = 0;

	UPROPERTY(BlueprintReadOnly)
	FString Prompt;

	UPROPERTY(BlueprintReadOnly)
	FString Response;

	UPROPERTY(BlueprintReadOnly)
	FString Explanation;

	UPROPERTY(BlueprintReadOnly)
	FString SourcePath;

	UPROPERTY(BlueprintReadOnly)
	bool bDailyDouble = false;

	UPROPERTY(BlueprintReadOnly)
	bool bResponseVisible = false;

	UPROPERTY(BlueprintReadOnly)
	int32 BuzzedTeamIndex = INDEX_NONE;

	UPROPERTY(BlueprintReadOnly)
	FString BuzzedTeamName;

	UPROPERTY(BlueprintReadOnly)
	FLinearColor BuzzedTeamColor = FLinearColor::White;

	UPROPERTY(BlueprintReadOnly)
	FString Notice;
};

namespace TJPalette
{
	inline FLinearColor Hex(const TCHAR* Rgb)
	{
		return FLinearColor(FColor::FromHex(FString(Rgb)));
	}

	inline FLinearColor Ink() { return Hex(TEXT("0F1B33")); }
	inline FLinearColor Gold() { return Hex(TEXT("F0D060")); }
	inline FLinearColor GoldDeep() { return Hex(TEXT("C9A227")); }
	inline FLinearColor Board() { return Hex(TEXT("0B3D91")); }
	inline FLinearColor BoardCell() { return Hex(TEXT("1152C0")); }
	inline FLinearColor BoardBorder() { return Hex(TEXT("071833")); }
	inline FLinearColor CellAnswered() { return Hex(TEXT("0A274F")); }
	inline FLinearColor Danger() { return Hex(TEXT("E85D4C")); }
	inline FLinearColor Ok() { return Hex(TEXT("3ECF8E")); }
	inline FLinearColor Text() { return Hex(TEXT("F4F7FF")); }
	inline FLinearColor Muted() { return Hex(TEXT("A9B7D4")); }
	inline FLinearColor Answer() { return Hex(TEXT("B9F5D4")); }
	inline FLinearColor Background() { return Hex(TEXT("071225")); }
	inline FLinearColor Panel() { return FLinearColor(Hex(TEXT("0A1830")).R, Hex(TEXT("0A1830")).G, Hex(TEXT("0A1830")).B, 0.94f); }
}

struct FTJStage
{
	static constexpr float CardWidth = 150.f;
	static constexpr float CardHeight = 88.f;
	static constexpr float Gap = 12.f;
	static constexpr float BoardBottom = 160.f;
};

inline FString TJFormatMoney(int32 Score)
{
	return FString::Printf(TEXT("%s$%d"), Score < 0 ? TEXT("-") : TEXT(""), FMath::Abs(Score));
}

inline const TCHAR* TJPhaseLabel(ETJPhase Phase)
{
	switch (Phase)
	{
	case ETJPhase::Lobby: return TEXT("Lobby");
	case ETJPhase::Board: return TEXT("Board");
	case ETJPhase::HostPreview: return TEXT("Host reading");
	case ETJPhase::ClueOpen: return TEXT("Buzzers open");
	case ETJPhase::BuzzLocked: return TEXT("Buzz locked");
	case ETJPhase::AnswerRevealed: return TEXT("Answer");
	case ETJPhase::Final: return TEXT("Final");
	case ETJPhase::Finished: return TEXT("Finished");
	default: return TEXT("Board");
	}
}

inline FString TJHintFor(const FTJMatchView& View)
{
	if (!View.Notice.IsEmpty() && View.Phase == ETJPhase::ClueOpen)
	{
		return View.Notice + TEXT(" · 1 2 3 buzz · R reveals");
	}
	switch (View.Phase)
	{
	case ETJPhase::Lobby:
		return TEXT("Enter takes the stage");
	case ETJPhase::Board:
		return TEXT("Click a dollar amount");
	case ETJPhase::HostPreview:
		return TEXT("Host only · ") + View.Prompt
			+ (View.SourcePath.IsEmpty() ? FString() : TEXT(" · ") + View.SourcePath)
			+ TEXT(" · Enter opens buzzers · R reveals");
	case ETJPhase::ClueOpen:
		return TEXT("1 Platform · 2 QA · 3 Clients · Space buzzes Platform · R reveals");
	case ETJPhase::BuzzLocked:
		return TEXT("C correct · X incorrect · R reveals");
	case ETJPhase::AnswerRevealed:
		return TEXT("Esc back to the board");
	case ETJPhase::Finished:
		return TEXT("Enter plays the board again");
	default:
		return FString();
	}
}
