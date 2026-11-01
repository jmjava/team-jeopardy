#pragma once

#include "CoreMinimal.h"
#include "GameFramework/PlayerController.h"
#include "TJTypes.h"
#include "TJPlayerController.generated.h"

class ATJCardActor;
class STJPlayHint;

/** Host keys from the web client, plus 1/2/3 to buzz as a podium. */
UCLASS()
class TEAMJEOPARDY_API ATJPlayerController : public APlayerController
{
	GENERATED_BODY()

public:
	ATJPlayerController();

	virtual void BeginPlay() override;
	virtual void EndPlay(const EEndPlayReason::Type EndPlayReason) override;
	virtual void SetupInputComponent() override;
	virtual void PlayerTick(float DeltaSeconds) override;

	void ApplyView(const FTJMatchView& View);

private:
	void CreateHint();
	void SelectUnderCursor();
	void UpdateHover();
	void OnAdvance();
	void OnBuzzPlatform();
	void OnBuzzQa();
	void OnBuzzClients();
	void OnCorrect();
	void OnIncorrect();
	void OnReveal();
	void OnBack();

	TSharedPtr<STJPlayHint> Hint;
	FTJMatchView Latest;

	UPROPERTY()
	TObjectPtr<ATJCardActor> Hovered;
};
