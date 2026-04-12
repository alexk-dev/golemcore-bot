export interface HookTemplateExample {
  path: string;
  description: string;
}

export const HOOK_TEMPLATE_PATH_HINT =
  'Paths are resolved from the incoming JSON body root. Use payload.* only when your tester wraps the original event under a payload field. Full JSONPath starting with $ is also supported.';

export const HOOK_TEMPLATE_EXAMPLES: HookTemplateExample[] = [
  {
    path: '{request.command}',
    description: 'Raw Alice request body: reads the spoken command directly.',
  },
  {
    path: '{payload.request.command}',
    description: 'Wrapped tester payload: use this when the original body is nested under payload.',
  },
  {
    path: '{payload.meta.client_id}',
    description: 'Wrapped tester metadata: resolves nested fields from the payload envelope.',
  },
  {
    path: '{$.payload.meta.client_id}',
    description: 'Explicit JSONPath form starting at the root document.',
  },
  {
    path: '{items[0].id}',
    description: 'Array traversal with bracket notation.',
  },
];
