import { useCallback, useEffect, useRef, useState } from "react";
import useSWRMutation from "swr/mutation";
import type { TelegramApiResult } from "@/lib/types";
import { telegramApi, type TelegramApiArg } from "@/lib/api";
import { useWebsocket } from "@/hooks/use-websocket";

export function useTelegramMethod() {
  const pendingRequestsRef = useRef<
    Map<
      string,
      {
        resolve: (value: any) => void;
        reject: (reason?: any) => void;
      }
    >
  >(new Map());

  const [pendingCount, setPendingCount] = useState(0);

  const { lastJsonMessage } = useWebsocket();

  const [lastMethod, setLastMethod] = useState<{
    code: string | null;
    result: unknown;
  }>({
    code: null,
    result: null,
  });

  useEffect(() => {
    if (!lastJsonMessage?.code) return;

    const code = lastJsonMessage.code;
    const data = lastJsonMessage.data;

    setLastMethod({ code, result: data });

    const pendingRequest = pendingRequestsRef.current.get(code);
    if (pendingRequest) {
      pendingRequest.resolve(data);
      pendingRequestsRef.current.delete(code);
      setPendingCount(pendingRequestsRef.current.size);
    }
  }, [lastJsonMessage]);

  const { trigger, isMutating } = useSWRMutation<
    TelegramApiResult,
    Error,
    string,
    TelegramApiArg
  >("/telegram/api", telegramApi);

  const executeMethod = useCallback(
    async (arg: TelegramApiArg): Promise<any> => {
      try {
        const result = await trigger(arg);
        const { code } = result;

        return new Promise((resolve, reject) => {
          const timeoutId = setTimeout(() => {
            pendingRequestsRef.current.delete(code);
            setPendingCount(pendingRequestsRef.current.size);
            reject(new Error(`Request timeout for code: ${code}`));
          }, 30000);

          pendingRequestsRef.current.set(code, {
            resolve: (value) => {
              clearTimeout(timeoutId);
              pendingRequestsRef.current.delete(code);
              setPendingCount(pendingRequestsRef.current.size);
              resolve(value);
            },
            reject: (reason) => {
              clearTimeout(timeoutId);
              pendingRequestsRef.current.delete(code);
              setPendingCount(pendingRequestsRef.current.size);
              reject(
                reason instanceof Error ? reason : new Error(String(reason)),
              );
            },
          });

          setPendingCount(pendingRequestsRef.current.size);
        });
      } catch (error) {
        throw error;
      }
    },
    [trigger],
  );

  const isMethodExecuting = isMutating || pendingCount > 0;

  return {
    executeMethod,
    triggerMethod: trigger,
    isMethodExecuting,
    lastMethodCode: lastMethod.code,
    lastMethodResult: lastMethod.result,
    pendingRequestsCount: pendingCount,
  };
}
