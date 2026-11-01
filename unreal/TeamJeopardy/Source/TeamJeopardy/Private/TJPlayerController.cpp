#include "TJPlayerController.h"

#include "Engine/Engine.h"
#include "Engine/GameViewportClient.h"
#include "InputCoreTypes.h"
#include "Styling/CoreStyle.h"
#include "TJCardActor.h"
#include "TJGameMode.h"
#include "Widgets/Layout/SBorder.h"
#include "Widgets/SCompoundWidget.h"
#include "Widgets/SOverlay.h"
#include "Widgets/Text/STextBlock.h"

class STJPlayHint : public SCompoundWidget
{
public:
	SLATE_BEGIN_ARGS(STJPlayHint) {}
	SLATE_END_ARGS()

	void Construct(const FArguments& InArgs)
	{
		ChildSlot
		[
			SNew(SOverlay)
			+ SOverlay::Slot().HAlign(HAlign_Left).VAlign(VAlign_Top).Padding(FMargin(28.f, 20.f))
			[
				SNew(SBorder)
				.BorderImage(FCoreStyle::Get().GetBrush("WhiteBrush"))
				.BorderBackgroundColor(FLinearColor(0.02f, 0.05f, 0.1f, 0.72f))
				.Padding(FMargin(12.f, 6.f))
				[
					SAssignNew(PhaseText, STextBlock)
					.Font(FCoreStyle::GetDefaultFontStyle("Bold", 16))
					.ColorAndOpacity(FSlateColor(TJPalette::Gold()))
				]
			]
			+ SOverlay::Slot().HAlign(HAlign_Fill).VAlign(VAlign_Bottom).Padding(FMargin(28.f, 16.f))
			[
				SNew(SBorder)
				.BorderImage(FCoreStyle::Get().GetBrush("WhiteBrush"))
				.BorderBackgroundColor(FLinearColor(0.02f, 0.05f, 0.1f, 0.78f))
				.Padding(FMargin(14.f, 8.f))
				[
					SAssignNew(HintText, STextBlock)
					.AutoWrapText(true)
					.Font(FCoreStyle::GetDefaultFontStyle("Regular", 16))
					.ColorAndOpacity(FSlateColor(TJPalette::Text()))
				]
			]
		];
	}

	void SetLines(const FString& Phase, const FString& Hint) const
	{
		if (PhaseText.IsValid())
		{
			PhaseText->SetText(FText::FromString(Phase));
		}
		if (HintText.IsValid())
		{
			HintText->SetText(FText::FromString(Hint));
		}
	}

private:
	TSharedPtr<STextBlock> PhaseText;
	TSharedPtr<STextBlock> HintText;
};

ATJPlayerController::ATJPlayerController()
{
	bShowMouseCursor = true;
	bEnableClickEvents = true;
	bEnableMouseOverEvents = true;
}

void ATJPlayerController::BeginPlay()
{
	Super::BeginPlay();
	FInputModeGameAndUI Mode;
	Mode.SetHideCursorDuringCapture(false);
	Mode.SetLockMouseToViewportBehavior(EMouseLockMode::DoNotLock);
	SetInputMode(Mode);
	CreateHint();
	if (ATJGameMode* ModeRules = GetWorld() ? GetWorld()->GetAuthGameMode<ATJGameMode>() : nullptr)
	{
		ApplyView(ModeRules->MakeView());
	}
}

void ATJPlayerController::EndPlay(const EEndPlayReason::Type EndPlayReason)
{
	if (Hint.IsValid() && GEngine && GEngine->GameViewport)
	{
		GEngine->GameViewport->RemoveViewportWidgetContent(Hint.ToSharedRef());
	}
	Hint.Reset();
	Super::EndPlay(EndPlayReason);
}

void ATJPlayerController::SetupInputComponent()
{
	Super::SetupInputComponent();
	if (!InputComponent)
	{
		return;
	}
	InputComponent->BindKey(EKeys::Enter, IE_Pressed, this, &ATJPlayerController::OnAdvance);
	InputComponent->BindKey(EKeys::O, IE_Pressed, this, &ATJPlayerController::OnAdvance);
	InputComponent->BindKey(EKeys::SpaceBar, IE_Pressed, this, &ATJPlayerController::OnBuzzPlatform);
	InputComponent->BindKey(EKeys::One, IE_Pressed, this, &ATJPlayerController::OnBuzzPlatform);
	InputComponent->BindKey(EKeys::Two, IE_Pressed, this, &ATJPlayerController::OnBuzzQa);
	InputComponent->BindKey(EKeys::Three, IE_Pressed, this, &ATJPlayerController::OnBuzzClients);
	InputComponent->BindKey(EKeys::C, IE_Pressed, this, &ATJPlayerController::OnCorrect);
	InputComponent->BindKey(EKeys::Y, IE_Pressed, this, &ATJPlayerController::OnCorrect);
	InputComponent->BindKey(EKeys::X, IE_Pressed, this, &ATJPlayerController::OnIncorrect);
	InputComponent->BindKey(EKeys::N, IE_Pressed, this, &ATJPlayerController::OnIncorrect);
	InputComponent->BindKey(EKeys::R, IE_Pressed, this, &ATJPlayerController::OnReveal);
	InputComponent->BindKey(EKeys::Escape, IE_Pressed, this, &ATJPlayerController::OnBack);
	InputComponent->BindKey(EKeys::B, IE_Pressed, this, &ATJPlayerController::OnBack);
	InputComponent->BindKey(EKeys::LeftMouseButton, IE_Pressed, this, &ATJPlayerController::SelectUnderCursor);
}

void ATJPlayerController::PlayerTick(float DeltaSeconds)
{
	Super::PlayerTick(DeltaSeconds);
	if (!Hint.IsValid())
	{
		CreateHint();
	}
	UpdateHover();
}

void ATJPlayerController::ApplyView(const FTJMatchView& View)
{
	Latest = View;
	if (Hint.IsValid())
	{
		Hint->SetLines(TJPhaseLabel(View.Phase), TJHintFor(View));
	}
}

void ATJPlayerController::CreateHint()
{
	if (Hint.IsValid() || !GEngine || !GEngine->GameViewport)
	{
		return;
	}
	Hint = SNew(STJPlayHint);
	GEngine->GameViewport->AddViewportWidgetContent(Hint.ToSharedRef(), 20);
	Hint->SetLines(TJPhaseLabel(Latest.Phase), TJHintFor(Latest));
}

void ATJPlayerController::SelectUnderCursor()
{
	FHitResult Hit;
	if (!GetHitResultUnderCursor(ECC_Visibility, false, Hit))
	{
		return;
	}
	ATJCardActor* Card = Cast<ATJCardActor>(Hit.GetActor());
	if (!Card || Card->IsHeader() || Card->IsAnswered())
	{
		return;
	}
	if (ATJGameMode* Mode = GetWorld() ? GetWorld()->GetAuthGameMode<ATJGameMode>() : nullptr)
	{
		Mode->SelectClue(Card->GetCategoryIndex(), Card->GetClueIndex());
	}
}

void ATJPlayerController::UpdateHover()
{
	ATJCardActor* Under = nullptr;
	if (Latest.Phase == ETJPhase::Board)
	{
		FHitResult Hit;
		if (GetHitResultUnderCursor(ECC_Visibility, false, Hit))
		{
			Under = Cast<ATJCardActor>(Hit.GetActor());
			if (Under && (Under->IsHeader() || Under->IsAnswered()))
			{
				Under = nullptr;
			}
		}
	}
	if (Under == Hovered)
	{
		return;
	}
	if (Hovered)
	{
		Hovered->SetHighlighted(false);
	}
	Hovered = Under;
	if (Hovered)
	{
		Hovered->SetHighlighted(true);
	}
}

void ATJPlayerController::OnAdvance()
{
	ATJGameMode* Mode = GetWorld() ? GetWorld()->GetAuthGameMode<ATJGameMode>() : nullptr;
	if (!Mode)
	{
		return;
	}
	if (Latest.Phase == ETJPhase::Lobby || Latest.Phase == ETJPhase::Finished)
	{
		Mode->StartRound();
	}
	else
	{
		Mode->OpenBuzzers();
	}
}

void ATJPlayerController::OnBuzzPlatform()
{
	if (ATJGameMode* Mode = GetWorld() ? GetWorld()->GetAuthGameMode<ATJGameMode>() : nullptr)
	{
		Mode->Buzz(0);
	}
}

void ATJPlayerController::OnBuzzQa()
{
	if (ATJGameMode* Mode = GetWorld() ? GetWorld()->GetAuthGameMode<ATJGameMode>() : nullptr)
	{
		Mode->Buzz(1);
	}
}

void ATJPlayerController::OnBuzzClients()
{
	if (ATJGameMode* Mode = GetWorld() ? GetWorld()->GetAuthGameMode<ATJGameMode>() : nullptr)
	{
		Mode->Buzz(2);
	}
}

void ATJPlayerController::OnCorrect()
{
	if (ATJGameMode* Mode = GetWorld() ? GetWorld()->GetAuthGameMode<ATJGameMode>() : nullptr)
	{
		Mode->Judge(true);
	}
}

void ATJPlayerController::OnIncorrect()
{
	if (ATJGameMode* Mode = GetWorld() ? GetWorld()->GetAuthGameMode<ATJGameMode>() : nullptr)
	{
		Mode->Judge(false);
	}
}

void ATJPlayerController::OnReveal()
{
	if (ATJGameMode* Mode = GetWorld() ? GetWorld()->GetAuthGameMode<ATJGameMode>() : nullptr)
	{
		Mode->Reveal();
	}
}

void ATJPlayerController::OnBack()
{
	if (ATJGameMode* Mode = GetWorld() ? GetWorld()->GetAuthGameMode<ATJGameMode>() : nullptr)
	{
		Mode->ReturnToBoard();
	}
}
