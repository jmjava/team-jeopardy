#pragma once

#include "CoreMinimal.h"
#include "GameFramework/GameModeBase.h"
#include "TJTypes.h"
#include "TJGameMode.generated.h"

class ATJStudio;

/**
 * Local hot-seat round. The sequence matches unreal/preview/match.js and
 * GameRoomService: preview, one buzz, a miss reopens, a correct answer reveals.
 */
UCLASS()
class TEAMJEOPARDY_API ATJGameMode : public AGameModeBase
{
	GENERATED_BODY()

public:
	ATJGameMode();

	virtual void BeginPlay() override;

	void StartRound();
	void SelectClue(int32 CategoryIndex, int32 ClueIndex);
	void OpenBuzzers();
	void Buzz(int32 TeamIndex);
	void Judge(bool bCorrect);
	void Reveal();
	void ReturnToBoard();

	FTJMatchView MakeView() const;

private:
	void Publish();
	void LoadBoard();
	void AddTeam(const TCHAR* Id, const TCHAR* Name, const TCHAR* HexRgb);
	const FTJClue* FindActiveClue() const;
	int32 ClueCount() const;
	void ClearClue();

	UPROPERTY()
	TObjectPtr<ATJStudio> Studio;

	UPROPERTY()
	FTJBoard Board;

	UPROPERTY()
	TArray<FTJTeam> Teams;

	UPROPERTY()
	TArray<FString> AnsweredIds;

	TArray<int32> LockedTeams;
	ETJPhase Phase = ETJPhase::Lobby;
	int32 ActiveCategory = INDEX_NONE;
	int32 ActiveClueIndex = INDEX_NONE;
	int32 BuzzedTeam = INDEX_NONE;
	bool bResponseVisible = false;
	FString Notice;
};
