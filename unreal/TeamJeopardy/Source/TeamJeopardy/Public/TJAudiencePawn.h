#pragma once

#include "Camera/CameraComponent.h"
#include "CoreMinimal.h"
#include "GameFramework/Pawn.h"
#include "TJAudiencePawn.generated.h"

/** Locked audience camera. It frames the board once the studio has built it. */
UCLASS()
class TEAMJEOPARDY_API ATJAudiencePawn : public APawn
{
	GENERATED_BODY()

public:
	ATJAudiencePawn();

	virtual void Tick(float DeltaSeconds) override;

private:
	UPROPERTY()
	TObjectPtr<UCameraComponent> Camera;
};
