#include "TJGameMode.h"

#include "Kismet/GameplayStatics.h"
#include "TJAudiencePawn.h"
#include "TJPlayerController.h"
#include "TJSampleBoard.h"
#include "TJStudio.h"
#include "TeamJeopardy.h"

ATJGameMode::ATJGameMode()
{
	DefaultPawnClass = ATJAudiencePawn::StaticClass();
	PlayerControllerClass = ATJPlayerController::StaticClass();
}

void ATJGameMode::BeginPlay()
{
	Super::BeginPlay();
	LoadBoard();

	FActorSpawnParameters Params;
	Params.SpawnCollisionHandlingOverride = ESpawnActorCollisionHandlingMethod::AlwaysSpawn;
	Studio = GetWorld()->SpawnActor<ATJStudio>(ATJStudio::StaticClass(), FTransform::Identity, Params);
	if (Studio)
	{
		Studio->BuildBoard(Board);
	}
	Publish();
}

void ATJGameMode::StartRound()
{
	Notice.Reset();
	if (Phase == ETJPhase::Finished)
	{
		for (FTJTeam& Team : Teams)
		{
			Team.Score = 0;
		}
		AnsweredIds.Reset();
		ClearClue();
		Phase = ETJPhase::Board;
		Publish();
		return;
	}
	if (Phase != ETJPhase::Lobby)
	{
		return;
	}
	Phase = ETJPhase::Board;
	Publish();
}

void ATJGameMode::SelectClue(int32 CategoryIndex, int32 ClueIndex)
{
	Notice.Reset();
	if (Phase != ETJPhase::Board || !Board.Categories.IsValidIndex(CategoryIndex))
	{
		return;
	}
	const FTJCategory& Category = Board.Categories[CategoryIndex];
	if (!Category.Clues.IsValidIndex(ClueIndex))
	{
		return;
	}
	const FTJClue& Clue = Category.Clues[ClueIndex];
	if (AnsweredIds.Contains(Clue.Id))
	{
		return;
	}
	ActiveCategory = CategoryIndex;
	ActiveClueIndex = ClueIndex;
	BuzzedTeam = INDEX_NONE;
	LockedTeams.Reset();
	bResponseVisible = false;
	Phase = ETJPhase::HostPreview;
	Publish();
}

void ATJGameMode::OpenBuzzers()
{
	Notice.Reset();
	if (Phase != ETJPhase::HostPreview)
	{
		return;
	}
	Phase = ETJPhase::ClueOpen;
	Publish();
}

void ATJGameMode::Buzz(int32 TeamIndex)
{
	if (Phase != ETJPhase::ClueOpen || !Teams.IsValidIndex(TeamIndex))
	{
		return;
	}
	if (LockedTeams.Contains(TeamIndex))
	{
		Notice = Teams[TeamIndex].Name + TEXT(" already answered this clue");
		Publish();
		return;
	}
	Notice.Reset();
	BuzzedTeam = TeamIndex;
	Phase = ETJPhase::BuzzLocked;
	Publish();
}

void ATJGameMode::Judge(bool bCorrect)
{
	Notice.Reset();
	if (Phase != ETJPhase::BuzzLocked || !Teams.IsValidIndex(BuzzedTeam))
	{
		return;
	}
	const FTJClue* Clue = FindActiveClue();
	if (!Clue)
	{
		return;
	}
	Teams[BuzzedTeam].Score += bCorrect ? Clue->Value : -Clue->Value;
	if (bCorrect)
	{
		AnsweredIds.AddUnique(Clue->Id);
		bResponseVisible = true;
		Phase = ETJPhase::AnswerRevealed;
		Publish();
		return;
	}
	LockedTeams.AddUnique(BuzzedTeam);
	BuzzedTeam = INDEX_NONE;
	Phase = ETJPhase::ClueOpen;
	if (LockedTeams.Num() >= Teams.Num())
	{
		Notice = TEXT("Every podium is locked out");
	}
	Publish();
}

void ATJGameMode::Reveal()
{
	Notice.Reset();
	const FTJClue* Clue = FindActiveClue();
	if (!Clue)
	{
		return;
	}
	if (Phase == ETJPhase::AnswerRevealed && bResponseVisible)
	{
		return;
	}
	if (Phase != ETJPhase::HostPreview && Phase != ETJPhase::ClueOpen && Phase != ETJPhase::BuzzLocked)
	{
		return;
	}
	AnsweredIds.AddUnique(Clue->Id);
	bResponseVisible = true;
	Phase = ETJPhase::AnswerRevealed;
	Publish();
}

void ATJGameMode::ReturnToBoard()
{
	if (Phase != ETJPhase::AnswerRevealed)
	{
		return;
	}
	Notice.Reset();
	ClearClue();
	Phase = AnsweredIds.Num() >= ClueCount() ? ETJPhase::Finished : ETJPhase::Board;
	Publish();
}

FTJMatchView ATJGameMode::MakeView() const
{
	FTJMatchView View;
	View.Phase = Phase;
	View.BoardTitle = Board.Title;
	View.Teams = Teams;
	View.AnsweredClueIds = AnsweredIds;
	View.BuzzedTeamIndex = BuzzedTeam;
	View.bResponseVisible = bResponseVisible;
	View.Notice = Notice;
	if (Teams.IsValidIndex(BuzzedTeam))
	{
		View.BuzzedTeamName = Teams[BuzzedTeam].Name;
		View.BuzzedTeamColor = Teams[BuzzedTeam].Color;
	}
	if (Board.Categories.IsValidIndex(ActiveCategory))
	{
		View.CategoryTitle = Board.Categories[ActiveCategory].Title;
	}
	if (const FTJClue* Clue = FindActiveClue())
	{
		View.ClueValue = Clue->Value;
		View.Prompt = Clue->Prompt;
		View.Response = Clue->Response;
		View.Explanation = Clue->Explanation;
		View.SourcePath = Clue->SourcePath;
		View.bDailyDouble = Clue->bDailyDouble;
	}
	return View;
}

void ATJGameMode::Publish()
{
	const FTJMatchView View = MakeView();
	if (Studio)
	{
		Studio->Sync(View);
	}
	if (APlayerController* Controller = UGameplayStatics::GetPlayerController(this, 0))
	{
		if (ATJPlayerController* Player = Cast<ATJPlayerController>(Controller))
		{
			Player->ApplyView(View);
		}
	}
	UE_LOG(LogTeamJeopardy, Log, TEXT("Phase %s"), TJPhaseLabel(Phase));
}

void ATJGameMode::LoadBoard()
{
	FString Error;
	if (!TJLoadSampleBoard(Board, Error))
	{
		UE_LOG(LogTeamJeopardy, Error, TEXT("%s"), *Error);
		Board = FTJBoard();
		Board.Title = TEXT("Team Jeopardy");
		FTJCategory Category;
		Category.Id = TEXT("fallback");
		Category.Title = TEXT("STUDIO");
		FTJClue Clue;
		Clue.Id = TEXT("fallback-200");
		Clue.Value = 200;
		Clue.Prompt = TEXT("The sample board file did not load. The stage is still up.");
		Clue.Response = TEXT("What is Content/Boards/sample-board.json?");
		Clue.Explanation = TEXT("TJGameMode reads that file when the match starts.");
		Clue.SourcePath = TEXT("Content/Boards/sample-board.json");
		Category.Clues.Add(Clue);
		Board.Categories.Add(Category);
	}

	Teams.Reset();
	AddTeam(TEXT("platform"), TEXT("Platform"), TEXT("E8C547"));
	AddTeam(TEXT("qa"), TEXT("QA"), TEXT("3D8BFF"));
	AddTeam(TEXT("clients"), TEXT("Clients"), TEXT("E85D4C"));
}

void ATJGameMode::AddTeam(const TCHAR* Id, const TCHAR* Name, const TCHAR* HexRgb)
{
	FTJTeam Team;
	Team.Id = Id;
	Team.Name = Name;
	Team.Score = 0;
	Team.Color = TJPalette::Hex(HexRgb);
	Teams.Add(Team);
}

const FTJClue* ATJGameMode::FindActiveClue() const
{
	if (!Board.Categories.IsValidIndex(ActiveCategory))
	{
		return nullptr;
	}
	const TArray<FTJClue>& Clues = Board.Categories[ActiveCategory].Clues;
	if (!Clues.IsValidIndex(ActiveClueIndex))
	{
		return nullptr;
	}
	return &Clues[ActiveClueIndex];
}

int32 ATJGameMode::ClueCount() const
{
	int32 Total = 0;
	for (const FTJCategory& Category : Board.Categories)
	{
		Total += Category.Clues.Num();
	}
	return Total;
}

void ATJGameMode::ClearClue()
{
	ActiveCategory = INDEX_NONE;
	ActiveClueIndex = INDEX_NONE;
	BuzzedTeam = INDEX_NONE;
	LockedTeams.Reset();
	bResponseVisible = false;
}
