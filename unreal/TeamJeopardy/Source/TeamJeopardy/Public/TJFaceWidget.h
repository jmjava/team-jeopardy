#pragma once

#include "Blueprint/UserWidget.h"
#include "CoreMinimal.h"
#include "TJTypes.h"
#include "TJFaceWidget.generated.h"

class STextBlock;

/** Dollar amount or category title drawn on a physical card. */
UCLASS()
class TEAMJEOPARDY_API UTJFaceWidget : public UUserWidget
{
	GENERATED_BODY()

public:
	void SetFace(const FText& InLabel, ETJFaceKind InKind);

protected:
	virtual TSharedRef<SWidget> RebuildWidget() override;

private:
	void Refresh();

	FText Label;
	ETJFaceKind Kind = ETJFaceKind::Value;
	TSharedPtr<STextBlock> TextBlock;
};
