#include "TJClueWallWidget.h"

#include "Styling/CoreStyle.h"
#include "Widgets/Layout/SBorder.h"
#include "Widgets/Layout/SBox.h"
#include "Widgets/SBoxPanel.h"
#include "Widgets/Text/STextBlock.h"

namespace
{
	void Show(const TSharedPtr<SVerticalBox>& Box, bool bVisible)
	{
		if (Box.IsValid())
		{
			Box->SetVisibility(bVisible ? EVisibility::Visible : EVisibility::Collapsed);
		}
	}
}

TSharedPtr<STextBlock> UTJClueWallWidget::MakeLine(int32 Size, const FLinearColor& Color, bool bBold) const
{
	return SNew(STextBlock)
		.Justification(ETextJustify::Center)
		.AutoWrapText(true)
		.WrapTextAt(900.f)
		.Font(FCoreStyle::GetDefaultFontStyle(bBold ? "Bold" : "Regular", Size))
		.ColorAndOpacity(FSlateColor(Color));
}

TSharedRef<SWidget> UTJClueWallWidget::RebuildWidget()
{
	LobbyTitle = MakeLine(72, TJPalette::Text(), true);
	CategoryText = MakeLine(42, TJPalette::Text(), true);
	ValueText = MakeLine(64, TJPalette::Gold(), true);
	DailyDoubleText = MakeLine(28, TJPalette::Ink(), true);
	PromptText = MakeLine(40, TJPalette::Text(), false);
	SourceText = MakeLine(22, TJPalette::Muted(), false);
	BuzzText = MakeLine(72, TJPalette::Gold(), true);
	ResponseText = MakeLine(48, TJPalette::Answer(), true);
	ExplanationText = MakeLine(24, TJPalette::Muted(), false);
	ScoreText = MakeLine(36, TJPalette::Gold(), true);

	auto Kicker = [](const TCHAR* Text)
	{
		return SNew(STextBlock)
			.Text(FText::FromString(Text))
			.Justification(ETextJustify::Center)
			.Font(FCoreStyle::GetDefaultFontStyle("Bold", 20))
			.ColorAndOpacity(FSlateColor(TJPalette::Gold()));
	};

	SAssignNew(LobbyBox, SVerticalBox)
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center).Padding(FMargin(0.f, 0.f, 0.f, 12.f))
		[
			Kicker(TEXT("AUDIENCE CAMERA"))
		]
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center).Padding(FMargin(0.f, 0.f, 0.f, 16.f))
		[
			LobbyTitle.ToSharedRef()
		]
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center)
		[
			SNew(STextBlock)
			.Text(FText::FromString(TEXT("The board, the clue wall, and three podiums. Same round as the web client.")))
			.Justification(ETextJustify::Center)
			.AutoWrapText(true)
			.WrapTextAt(860.f)
			.Font(FCoreStyle::GetDefaultFontStyle("Regular", 32))
			.ColorAndOpacity(FSlateColor(TJPalette::Text()))
		];

	SAssignNew(FinishedBox, SVerticalBox)
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center).Padding(FMargin(0.f, 0.f, 0.f, 12.f))
		[
			Kicker(TEXT("FINAL SCORES"))
		]
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center).Padding(FMargin(0.f, 0.f, 0.f, 18.f))
		[
			SNew(STextBlock)
			.Text(FText::FromString(TEXT("Game finished")))
			.Justification(ETextJustify::Center)
			.Font(FCoreStyle::GetDefaultFontStyle("Bold", 72))
			.ColorAndOpacity(FSlateColor(TJPalette::Text()))
		]
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center)
		[
			ScoreText.ToSharedRef()
		];

	TSharedRef<SBox> DailyDoubleBadge = SNew(SBox)
		[
			SNew(SBorder)
			.BorderImage(FCoreStyle::Get().GetBrush("WhiteBrush"))
			.BorderBackgroundColor(TJPalette::Gold())
			.Padding(FMargin(12.f, 4.f))
			[
				DailyDoubleText.ToSharedRef()
			]
		];
	DailyDoubleWrap = DailyDoubleBadge;

	SAssignNew(BannerBox, SVerticalBox)
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center)
		[
			SNew(SHorizontalBox)
			+ SHorizontalBox::Slot().AutoWidth().VAlign(VAlign_Center).Padding(FMargin(0.f, 0.f, 18.f, 0.f))
			[
				CategoryText.ToSharedRef()
			]
			+ SHorizontalBox::Slot().AutoWidth().VAlign(VAlign_Center).Padding(FMargin(0.f, 0.f, 18.f, 0.f))
			[
				ValueText.ToSharedRef()
			]
			+ SHorizontalBox::Slot().AutoWidth().VAlign(VAlign_Center)
			[
				DailyDoubleBadge
			]
		];

	SAssignNew(PreviewBox, SVerticalBox)
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center).Padding(FMargin(0.f, 28.f, 0.f, 8.f))
		[
			Kicker(TEXT("GET READY"))
		]
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center)
		[
			SNew(STextBlock)
			.Text(FText::FromString(TEXT("Host is reading the clue")))
			.Justification(ETextJustify::Center)
			.Font(FCoreStyle::GetDefaultFontStyle("Bold", 64))
			.ColorAndOpacity(FSlateColor(TJPalette::Text()))
		]
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center).Padding(FMargin(0.f, 12.f, 0.f, 0.f))
		[
			SNew(STextBlock)
			.Text(FText::FromString(TEXT("Buzzers open when the clue appears.")))
			.Justification(ETextJustify::Center)
			.Font(FCoreStyle::GetDefaultFontStyle("Regular", 28))
			.ColorAndOpacity(FSlateColor(TJPalette::Muted()))
		];

	SAssignNew(PromptBox, SVerticalBox)
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center).Padding(FMargin(0.f, 28.f, 0.f, 10.f))
		[
			Kicker(TEXT("ANSWER IN THE FORM OF A QUESTION"))
		]
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center)
		[
			PromptText.ToSharedRef()
		]
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center).Padding(FMargin(0.f, 10.f, 0.f, 0.f))
		[
			SourceText.ToSharedRef()
		];

	SAssignNew(BuzzBox, SVerticalBox)
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center).Padding(FMargin(0.f, 24.f, 0.f, 6.f))
		[
			Kicker(TEXT("FIRST BUZZ"))
		]
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center)
		[
			BuzzText.ToSharedRef()
		];

	SAssignNew(AnswerBox, SVerticalBox)
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center).Padding(FMargin(0.f, 24.f, 0.f, 6.f))
		[
			Kicker(TEXT("ANSWER"))
		]
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center)
		[
			ResponseText.ToSharedRef()
		]
		+ SVerticalBox::Slot().AutoHeight().HAlign(HAlign_Center).Padding(FMargin(0.f, 8.f, 0.f, 0.f))
		[
			ExplanationText.ToSharedRef()
		];

	return SNew(SBorder)
		.BorderImage(FCoreStyle::Get().GetBrush("WhiteBrush"))
		.BorderBackgroundColor(TJPalette::Panel())
		.Padding(FMargin(48.f))
		.HAlign(HAlign_Center)
		.VAlign(VAlign_Center)
		[
			SNew(SVerticalBox)
			+ SVerticalBox::Slot().AutoHeight()[LobbyBox.ToSharedRef()]
			+ SVerticalBox::Slot().AutoHeight()[FinishedBox.ToSharedRef()]
			+ SVerticalBox::Slot().AutoHeight()[BannerBox.ToSharedRef()]
			+ SVerticalBox::Slot().AutoHeight()[PreviewBox.ToSharedRef()]
			+ SVerticalBox::Slot().AutoHeight()[PromptBox.ToSharedRef()]
			+ SVerticalBox::Slot().AutoHeight()[BuzzBox.ToSharedRef()]
			+ SVerticalBox::Slot().AutoHeight()[AnswerBox.ToSharedRef()]
		];
}

void UTJClueWallWidget::ApplyView(const FTJMatchView& InView)
{
	View = InView;
	if (!LobbyBox.IsValid())
	{
		return;
	}

	const bool bLobby = View.Phase == ETJPhase::Lobby;
	const bool bFinished = View.Phase == ETJPhase::Finished;
	const bool bPreview = View.Phase == ETJPhase::HostPreview;
	const bool bPrompt = View.Phase == ETJPhase::ClueOpen
		|| View.Phase == ETJPhase::BuzzLocked
		|| View.Phase == ETJPhase::AnswerRevealed;
	const bool bClue = bPreview || bPrompt;

	Show(LobbyBox, bLobby);
	Show(FinishedBox, bFinished);
	Show(BannerBox, bClue);
	Show(PreviewBox, bPreview);
	Show(PromptBox, bPrompt);
	Show(BuzzBox, View.Phase == ETJPhase::BuzzLocked && !View.BuzzedTeamName.IsEmpty());
	Show(AnswerBox, View.bResponseVisible);

	if (LobbyTitle.IsValid())
	{
		LobbyTitle->SetText(FText::FromString(View.BoardTitle));
	}
	if (CategoryText.IsValid())
	{
		CategoryText->SetText(FText::FromString(View.CategoryTitle));
	}
	if (ValueText.IsValid())
	{
		ValueText->SetText(FText::FromString(TJFormatMoney(View.ClueValue)));
	}
	if (DailyDoubleText.IsValid())
	{
		DailyDoubleText->SetText(FText::FromString(TEXT("DAILY DOUBLE")));
	}
	if (DailyDoubleWrap.IsValid())
	{
		DailyDoubleWrap->SetVisibility(bClue && View.bDailyDouble ? EVisibility::Visible : EVisibility::Collapsed);
	}
	if (PromptText.IsValid())
	{
		PromptText->SetText(FText::FromString(View.Prompt));
	}
	if (SourceText.IsValid())
	{
		SourceText->SetText(FText::FromString(View.SourcePath));
		SourceText->SetVisibility(View.SourcePath.IsEmpty() ? EVisibility::Collapsed : EVisibility::Visible);
	}
	if (BuzzText.IsValid())
	{
		BuzzText->SetText(FText::FromString(View.BuzzedTeamName));
		BuzzText->SetColorAndOpacity(FSlateColor(View.BuzzedTeamColor));
	}
	if (ResponseText.IsValid())
	{
		ResponseText->SetText(FText::FromString(View.Response));
	}
	if (ExplanationText.IsValid())
	{
		ExplanationText->SetText(FText::FromString(View.Explanation));
	}
	if (ScoreText.IsValid())
	{
		FString Scores;
		for (int32 Index = 0; Index < View.Teams.Num(); ++Index)
		{
			if (Index > 0)
			{
				Scores += TEXT("    ");
			}
			Scores += View.Teams[Index].Name.ToUpper();
			Scores += TEXT(" ");
			Scores += TJFormatMoney(View.Teams[Index].Score);
		}
		ScoreText->SetText(FText::FromString(Scores));
	}
}
